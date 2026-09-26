package com.lacuna.signature;

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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The JSON API as the batch signature page uses it, against the local PKI Express installation, with
 * {@link TestSigner} standing in for Web PKI.
 */
@SpringBootTest(properties = "lacuna.pki-express.trust-lacuna-test-root=true")
@AutoConfigureMockMvc
@EnabledIf("com.lacuna.support.TestSigner#pkiExpressInstalled")
class SignatureApiTest {

    @TempDir
    static Path storageDir;

    static TestSigner signer;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("lacuna.storage.dir", storageDir::toString);
    }

    @BeforeAll
    static void loadSigner() throws Exception {
        signer = TestSigner.pierreDeFermat();
    }

    @Test
    void signsBatch() throws Exception {
        var ids = uploadBatch(
                new MockMultipartFile("files", "documento.pdf", null,
                        new ClassPathResource("samples/documento-exemplo.pdf").getContentAsByteArray()),
                new MockMultipartFile("files", "nota.txt", null, "nota".getBytes(StandardCharsets.UTF_8)));
        var certificate = signer.certificateBase64();

        call("/api/certificates/check", Map.of("certificate", certificate)).andExpect(status().isNoContent());
        var signedPdf = sign(ids.get(0), SignatureFormat.PADES, certificate);
        var signedText = sign(ids.get(1), SignatureFormat.CADES, certificate);

        assertThat(signedPdf.get("name").asString()).isEqualTo("documento.pdf");
        assertThat(signedPdf.get("signerName").asString()).isEqualTo(TestSigner.NAME);
        assertThat(signedText.get("name").asString()).isEqualTo("nota.txt.p7s");
        for (var signed : List.of(signedPdf, signedText)) {
            assertThat(signed.get("url").asString()).isEqualTo("http://localhost/documents/" + signed.get("id").asString());
            assertThat(reportOf(signed.get("id").asString()).signers()).singleElement().matches(SignerView::valid);
        }
    }

    @Test
    void refusesPadesForFilesOtherThanPdf() throws Exception {
        var ids = uploadBatch(new MockMultipartFile("files", "nota.txt", null, "nota".getBytes(StandardCharsets.UTF_8)));

        start(ids.get(0), SignatureFormat.PADES, signer.certificateBase64())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Somente arquivos PDF podem receber assinaturas PAdES."));
    }

    @Test
    void explainsBrokenPdf() throws Exception {
        var broken = "%PDF-1.4\nnão é um PDF de verdade\n".getBytes(StandardCharsets.UTF_8);
        var id = uploadBatch(new MockMultipartFile("files", "quebrado.pdf", null, broken)).get(0);

        start(id, SignatureFormat.PADES, signer.certificateBase64())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("O arquivo não é um PDF válido ou está corrompido."))
                .andExpect(jsonPath("$.report").value(containsString("InvalidPdfException")));
    }

    @Test
    void refusesCompletingTheSameSignatureTwice() throws Exception {
        var id = uploadBatch(new MockMultipartFile("files", "nota.txt", null, "nota".getBytes(StandardCharsets.UTF_8))).get(0);
        var certificate = signer.certificateBase64();
        var start = readJson(start(id, SignatureFormat.CADES, certificate).andExpect(status().isOk()));
        var completion = Map.of(
                "transferFileId", start.get("transferFileId").asString(),
                "signature", signer.sign(start.get("toSignHash").asString()),
                "certificate", certificate);
        call("/api/documents/" + id + "/signature/complete", completion).andExpect(status().isOk());

        call("/api/documents/" + id + "/signature/complete", completion)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("já foi concluída")));
    }

    @Test
    void answersNotFoundForUnknownDocument() throws Exception {
        start(UUID.randomUUID().toString(), SignatureFormat.CADES, signer.certificateBase64())
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void answersBadRequestForIncompleteRequest() throws Exception {
        call("/api/certificates/check", Map.of()).andExpect(status().isBadRequest());
    }

    private List<String> uploadBatch(MockMultipartFile... files) throws Exception {
        var request = multipart("/batch");
        for (var file : files) {
            request.file(file);
        }
        var signPage = mvc.perform(request).andReturn().getResponse().getRedirectedUrl();
        return UriComponentsBuilder.fromUriString(signPage).build().getQueryParams().get("documents");
    }

    /**
     * What batch-signature.js does for each file.
     */
    private JsonNode sign(String id, SignatureFormat format, String certificate) throws Exception {
        var start = readJson(start(id, format, certificate).andExpect(status().isOk()));
        return readJson(call("/api/documents/" + id + "/signature/complete", Map.of(
                "transferFileId", start.get("transferFileId").asString(),
                "signature", signer.sign(start.get("toSignHash").asString()),
                "certificate", certificate))
                .andExpect(status().isOk()));
    }

    private ResultActions start(String id, SignatureFormat format, String certificate) throws Exception {
        return call("/api/documents/" + id + "/signature/start", Map.of("format", format, "certificate", certificate));
    }

    private ResultActions call(String path, Map<String, ?> body) throws Exception {
        return mvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode readJson(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private SignatureReport reportOf(String id) throws Exception {
        return (SignatureReport) mvc.perform(get("/documents/" + id))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("report");
    }
}
