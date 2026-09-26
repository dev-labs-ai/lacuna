package com.lacuna.signature;

import com.lacuna.support.TestInfrastructure;
import com.lacuna.support.TestSigner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Signing with a certificate PKI Express does not trust: Lacuna's test certificate, with the test root not trusted.
 */
@SpringBootTest(properties = "lacuna.pki-express.trust-lacuna-test-root=false")
@ImportTestcontainers(TestInfrastructure.class)
@AutoConfigureMockMvc
@EnabledIf("com.lacuna.support.TestSigner#pkiExpressInstalled")
class UntrustedCertificateTest {

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
    void refusesCertificateBeforeTheUserSigns() throws Exception {
        var signPage = uploadSample(mvc);

        expectRejection(start(mvc, signPage), signPage);

        assertThat(storageDir.resolve("pkie-transfer")).isEmptyDirectory();
    }

    @Test
    void refusesCertificateOfBatchBeforeTheUserAuthorizesTheSignatures() throws Exception {
        checkCertificate(mvc)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Certificado não aceito"))
                .andExpect(jsonPath("$.detail").value(containsString("autoridade certificadora confiável")))
                .andExpect(jsonPath("$.report").value(containsString("Lacuna Root Test v3")));
    }

    /**
     * As in the dev profile: PKI Express only checks the certificate on completion.
     */
    @Nested
    @TestPropertySource(properties = "lacuna.signature.validate-certificate-on-selection=false")
    class WithoutValidationOnSelection {

        @Autowired
        MockMvc mvc;

        @Test
        void explainsRejectionOnCompletion() throws Exception {
            var signPage = uploadSample(mvc);
            var start = (SignatureStart) start(mvc, signPage)
                    .andExpect(status().isOk())
                    .andReturn().getModelAndView().getModel().get("start");

            var completion = mvc.perform(post(signPage + "/complete")
                    .param("transferFileId", start.transferFileId())
                    .param("signature", signer.sign(start.toSignHash()))
                    .param("certContent", signer.certificateBase64()));

            expectRejection(completion, signPage);
        }

        @Test
        void leavesTheCertificateOfBatchToBeCheckedOnCompletion() throws Exception {
            checkCertificate(mvc).andExpect(status().isNoContent());
        }
    }

    private static void expectRejection(ResultActions result, String signPage) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("title", "Certificado não aceito"))
                .andExpect(model().attribute("message",
                        "O certificado não foi emitido por uma autoridade certificadora confiável. "
                                + "(A raiz Lacuna Root Test v3 não é confiável)"))
                .andExpect(model().attribute("backUrl", signPage))
                .andExpect(model().attribute("backLabel", "Escolher outro certificado"))
                .andExpect(content().string(containsString("Relatório técnico")));
    }

    private static ResultActions checkCertificate(MockMvc mvc) throws Exception {
        return mvc.perform(post("/api/certificates/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"certificate\": \"" + signer.certificateBase64() + "\"}"));
    }

    private static String uploadSample(MockMvc mvc) throws Exception {
        return mvc.perform(post("/documents/sample"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private static ResultActions start(MockMvc mvc, String signPage) throws Exception {
        return mvc.perform(post(signPage + "/start")
                .param("format", SignatureFormat.PADES.name())
                .param("certThumb", "thumbprint-used-only-by-web-pki")
                .param("certContent", signer.certificateBase64()));
    }
}
