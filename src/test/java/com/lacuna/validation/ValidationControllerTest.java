package com.lacuna.validation;

import com.lacuna.pkiexpress.PkiExpressOperators;
import com.lacuna.signature.SignatureFormat;
import com.lacuna.signature.SignatureService;
import com.lacuna.support.TestSigner;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = "lacuna.pki-express.trust-lacuna-test-root=true")
@AutoConfigureMockMvc
@EnabledIf("com.lacuna.support.TestSigner#pkiExpressInstalled")
class ValidationControllerTest {

    @TempDir
    static Path storageDir;

    @TempDir
    Path workDir;

    static TestSigner signer;

    @Autowired
    MockMvc mvc;

    @Autowired
    SignatureService signatures;

    @Autowired
    PkiExpressOperators pkiExpress;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("lacuna.storage.dir", storageDir::toString);
    }

    @BeforeAll
    static void loadSigner() throws Exception {
        signer = TestSigner.pierreDeFermat();
    }

    @Test
    void rendersForm() throws Exception {
        mvc.perform(get("/validate"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"signedFile\"")));
    }

    @Test
    void validatesPadesSignature() throws Exception {
        var signed = sign(samplePdf(), SignatureFormat.PADES);

        var report = validate(upload("signedFile", "assinado.pdf", signed));

        assertThat(report.format()).isEqualTo(SignatureFormat.PADES);
        assertThat(report.signers()).singleElement().satisfies(result -> {
            assertThat(result.name()).isEqualTo(TestSigner.NAME);
            assertThat(result.valid()).as(result.report()).isTrue();
        });
    }

    @Test
    void validatesCadesSignature() throws Exception {
        var signed = sign(samplePdf(), SignatureFormat.CADES);

        var report = validate(upload("signedFile", "assinado.p7s", signed));

        assertThat(report.format()).isEqualTo(SignatureFormat.CADES);
        assertThat(report.contentIncluded()).isTrue();
        assertThat(report.signers()).singleElement().matches(SignerView::valid);
    }

    @Test
    void asksForOriginalFileOfDetachedCadesSignature() throws Exception {
        var signature = signDetached(samplePdf());

        mvc.perform(upload("signedFile", "destacada.p7s", signature))
                .andExpect(status().isUnprocessableContent())
                .andExpect(view().name("validate"))
                .andExpect(model().attribute("error", containsString("destacada")));
    }

    @Test
    void validatesDetachedCadesSignatureWithOriginalFile() throws Exception {
        var original = samplePdf();
        var signature = signDetached(original);

        var report = validate(upload("signedFile", "destacada.p7s", signature)
                .file(new MockMultipartFile("originalFile", "documento.pdf", null, Files.readAllBytes(original))));

        assertThat(report.contentIncluded()).isFalse();
        assertThat(report.signers()).singleElement().satisfies(result ->
                assertThat(result.valid()).as(result.report()).isTrue());
    }

    @Test
    void reportsDetachedCadesSignatureOfAnotherFileAsInvalid() throws Exception {
        var signature = signDetached(samplePdf());

        var report = validate(upload("signedFile", "destacada.p7s", signature)
                .file(new MockMultipartFile("originalFile", "outro.txt", null, "outro".getBytes(StandardCharsets.UTF_8))));

        assertThat(report.signers()).singleElement().satisfies(result -> {
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).isNotEmpty();
        });
    }

    @Test
    void summarizesResult() throws Exception {
        var signed = sign(sign(samplePdf(), SignatureFormat.PADES), SignatureFormat.PADES);

        mvc.perform(upload("signedFile", "assinado.pdf", signed))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Todas as 2 assinaturas são válidas.")));
    }

    @Test
    void reportsUnsignedPdfWithoutSignatures() throws Exception {
        mvc.perform(upload("signedFile", "documento.pdf", samplePdf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nenhuma assinatura encontrada")));
    }

    @Test
    void rejectsFilesThatCannotHoldSignatures() throws Exception {
        var text = Files.writeString(workDir.resolve("nota.txt"), "sem assinatura");

        mvc.perform(upload("signedFile", "nota.txt", text))
                .andExpect(status().isUnprocessableContent())
                .andExpect(model().attribute("error", containsString("não é um PDF nem uma assinatura CAdES")));
    }

    private SignatureReport validate(MockMultipartHttpServletRequestBuilder request) throws Exception {
        return (SignatureReport) mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(view().name("validate"))
                .andReturn().getModelAndView().getModel().get("report");
    }

    private static MockMultipartHttpServletRequestBuilder upload(String field, String name, Path file) throws Exception {
        return multipart("/validate").file(new MockMultipartFile(field, name, null, Files.readAllBytes(file)));
    }

    private Path samplePdf() throws Exception {
        var pdf = Files.createTempFile(workDir, "documento", ".pdf");
        try (var in = new ClassPathResource("samples/documento-exemplo.pdf").getInputStream()) {
            Files.copy(in, pdf, StandardCopyOption.REPLACE_EXISTING);
        }
        return pdf;
    }

    private Path sign(Path file, SignatureFormat format) throws Exception {
        var certificate = signer.certificateBase64();
        var start = signatures.start(file, format, certificate);
        var output = Files.createTempFile(workDir, "assinado", null);
        signatures.complete(file, start.transferFileId(), signer.sign(start.toSignHash()), certificate, output);
        return output;
    }

    /**
     * The application only produces attached CAdES signatures; detached ones come from other signers.
     */
    private Path signDetached(Path file) throws Exception {
        var certificate = signer.certificateBase64();
        var start = pkiExpress.execute(pkiExpress.cadesSignatureStarter(), starter -> {
            starter.setFileToSign(file);
            starter.setEncapsulateContent(false);
            starter.setCertificateBase64(certificate);
            return starter.start();
        });
        var output = Files.createTempFile(workDir, "destacada", ".p7s");
        signatures.complete(file, start.getTransferFile(), signer.sign(start.getToSignHash()), certificate, output);
        return output;
    }
}
