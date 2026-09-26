package com.lacuna.signature;

import com.lacuna.support.TestSigner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Signing with a certificate PKI Express does not trust: Lacuna's test certificate, with the test root not trusted.
 */
@SpringBootTest(properties = "lacuna.pki-express.trust-lacuna-test-root=false")
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
        assertThat(storageDir.resolve("pkie-transfer")).isEmptyDirectory();
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
