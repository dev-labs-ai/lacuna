package com.lacuna.signature;

import com.lacuna.document.DocumentFormat;
import com.lacuna.document.StoredDocument;
import com.lacuna.support.TestSigner;
import com.lacuna.validation.SignatureReport;
import com.lacuna.validation.SignerView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Runs the signature flows against the local PKI Express installation, with {@link TestSigner} standing in for
 * Web PKI.
 */
@SpringBootTest(properties = "lacuna.pki-express.trust-lacuna-test-root=true")
@AutoConfigureMockMvc
@EnabledIf("com.lacuna.support.TestSigner#pkiExpressInstalled")
class SignatureFlowTest {

    @TempDir
    static Path storageDir;

    static TestSigner signer;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("lacuna.storage.dir", storageDir::toString);
    }

    @BeforeAll
    static void loadSigner() throws Exception {
        signer = TestSigner.pierreDeFermat();
    }

    @Test
    void signsPdfWithPades() throws Exception {
        var signPage = uploadSample();
        mvc.perform(get(signPage))
                .andExpect(status().isOk())
                .andExpect(model().attribute("formats", List.of(SignatureFormat.PADES, SignatureFormat.CADES)));

        var start = start(signPage, SignatureFormat.PADES);
        assertThat(start.digestAlgorithm()).isEqualTo("SHA-256");
        var signed = complete(signPage, start);

        assertThat(documentAt(signed).format()).isEqualTo(DocumentFormat.PDF);
        assertThat(documentAt(signed).name()).isEqualTo("documento-exemplo.pdf");
        var report = reportAt(signed);
        assertThat(report.format()).isEqualTo(SignatureFormat.PADES);
        assertThat(report.signers()).singleElement().satisfies(signature -> {
            assertThat(signature.name()).isEqualTo(TestSigner.NAME);
            assertThat(signature.valid()).as(signature.report()).isTrue();
            assertThat(signature.signingTime()).isNotNull();
        });
        assertThat(storageDir.resolve("pkie-transfer").resolve(start.transferFileId())).doesNotExist();
    }

    @Test
    void signsPdfWithCadesKeepingTheOriginalInside() throws Exception {
        var signed = sign(uploadSample(), SignatureFormat.CADES);

        assertThat(documentAt(signed).format()).isEqualTo(DocumentFormat.CMS);
        assertThat(documentAt(signed).name()).isEqualTo("documento-exemplo.pdf.p7s");
        var report = reportAt(signed);
        assertThat(report.format()).isEqualTo(SignatureFormat.CADES);
        assertThat(report.contentIncluded()).isTrue();
        assertThat(report.signers()).singleElement().satisfies(signature ->
                assertThat(signature.valid()).as(signature.report()).isTrue());

        var original = new ClassPathResource("samples/documento-exemplo.pdf").getContentAsByteArray();
        mvc.perform(get(signed + "/content"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(original));
    }

    @Test
    void signsOtherFilesWithCadesOnly() throws Exception {
        var signPage = upload("contrato.txt", "Contrato de teste".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get(signPage))
                .andExpect(status().isOk())
                .andExpect(model().attribute("formats", List.of(SignatureFormat.CADES)));

        var signed = sign(signPage, SignatureFormat.CADES);

        assertThat(documentAt(signed).name()).isEqualTo("contrato.txt.p7s");
        assertThat(reportAt(signed).signers()).singleElement().matches(SignerView::valid);
    }

    @Test
    void rejectsPadesForFilesOtherThanPdf() throws Exception {
        var signPage = upload("contrato.txt", "Contrato de teste".getBytes(StandardCharsets.UTF_8));

        mvc.perform(post(signPage + "/start")
                        .param("format", "PADES")
                        .param("certThumb", "thumbprint")
                        .param("certContent", signer.certificateBase64()))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"));
    }

    @Test
    void cosignsCadesSignature() throws Exception {
        var signedOnce = sign(uploadSample(), SignatureFormat.CADES);

        var signedTwice = sign(signedOnce + "/sign", SignatureFormat.CADES);

        assertThat(documentAt(signedTwice).name()).isEqualTo("documento-exemplo.pdf.p7s");
        assertThat(reportAt(signedTwice).signers()).hasSize(2).allMatch(SignerView::valid);
    }

    @Test
    void addsSecondPadesSignature() throws Exception {
        var signedOnce = sign(uploadSample(), SignatureFormat.PADES);

        var signedTwice = sign(signedOnce + "/sign", SignatureFormat.PADES);

        assertThat(reportAt(signedTwice).signers()).hasSize(2).allMatch(SignerView::valid);
    }

    @Test
    void rejectsSignatureOfAnotherHash() throws Exception {
        var signPage = uploadSample();
        var start = start(signPage, SignatureFormat.PADES);
        var otherHash = Base64.getEncoder().encodeToString(new byte[32]);

        mvc.perform(post(signPage + "/complete")
                        .param("transferFileId", start.transferFileId())
                        .param("signature", signer.sign(otherHash)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("backUrl", signPage));

        assertThat(storageDir.resolve("pkie-transfer").resolve(start.transferFileId())).doesNotExist();
    }

    @Test
    void rejectsCompletingTheSameSignatureTwice() throws Exception {
        var signPage = uploadSample();
        var start = start(signPage, SignatureFormat.CADES);
        complete(signPage, start);

        mvc.perform(post(signPage + "/complete")
                        .param("transferFileId", start.transferFileId())
                        .param("signature", signer.sign(start.toSignHash())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("já foi concluída")));
    }

    @Test
    void rejectsTransferFileOutsideTransferFolder() throws Exception {
        var signPage = uploadSample();

        mvc.perform(post(signPage + "/complete")
                        .param("transferFileId", "../documents/x")
                        .param("signature", Base64.getEncoder().encodeToString(new byte[256])))
                .andExpect(status().isUnprocessableContent())
                .andExpect(view().name("error"));
    }

    @Test
    void showsUnsignedPdfWithoutSigners() throws Exception {
        var signPage = uploadSample();

        assertThat(reportAt(signPage.replace("/sign", "")).signers()).isEmpty();
    }

    /**
     * @return the signature page of the new document
     */
    private String uploadSample() throws Exception {
        return mvc.perform(post("/documents/sample"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private String upload(String name, byte[] content) throws Exception {
        return mvc.perform(multipart("/documents").file(new MockMultipartFile("file", name, null, content)))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    /**
     * @return the page of the signed document
     */
    private String sign(String signPage, SignatureFormat format) throws Exception {
        return complete(signPage, start(signPage, format));
    }

    private SignatureStart start(String signPage, SignatureFormat format) throws Exception {
        var model = mvc.perform(post(signPage + "/start")
                        .param("format", format.name())
                        .param("certThumb", "thumbprint-used-only-by-web-pki")
                        .param("certContent", signer.certificateBase64()))
                .andExpect(status().isOk())
                .andExpect(view().name("sign-complete"))
                .andReturn().getModelAndView().getModel();
        return (SignatureStart) model.get("start");
    }

    private String complete(String signPage, SignatureStart start) throws Exception {
        return mvc.perform(post(signPage + "/complete")
                        .param("transferFileId", start.transferFileId())
                        .param("signature", signer.sign(start.toSignHash())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("signedBy", TestSigner.NAME))
                .andReturn().getResponse().getRedirectedUrl();
    }

    private StoredDocument documentAt(String documentPage) throws Exception {
        return (StoredDocument) modelAt(documentPage).get("document");
    }

    private SignatureReport reportAt(String documentPage) throws Exception {
        return (SignatureReport) modelAt(documentPage).get("report");
    }

    private Map<String, Object> modelAt(String documentPage) throws Exception {
        return mvc.perform(get(documentPage))
                .andExpect(status().isOk())
                .andExpect(view().name("document"))
                .andReturn().getModelAndView().getModel();
    }
}
