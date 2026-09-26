package com.lacuna.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Document pages that do not need PKI Express.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentControllerTest {

    @TempDir
    static Path storageDir;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("lacuna.storage.dir", storageDir::toString);
    }

    @Test
    void rendersHomePage() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Enviar e assinar")));
    }

    @Test
    void uploadRedirectsToSignaturePage() throws Exception {
        var pdf = new MockMultipartFile("file", "contrato.pdf", "application/pdf", "%PDF-1.4\n".getBytes());

        mvc.perform(multipart("/documents").file(pdf))
                .andExpect(redirectedUrlPattern("/documents/*/sign"));
    }

    @Test
    void offersPadesAndCadesForPdf() throws Exception {
        var signPage = mvc.perform(post("/documents/sample")).andReturn().getResponse().getRedirectedUrl();

        mvc.perform(get(signPage))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lacuna-web-pki-2.18.0.min.js")))
                .andExpect(content().string(containsString("data-step=\"start\"")))
                .andExpect(content().string(containsString("type=\"radio\" name=\"format\" value=\"PADES\"")))
                .andExpect(content().string(containsString("type=\"radio\" name=\"format\" value=\"CADES\"")));
    }

    @Test
    void offersOnlyCadesForOtherFiles() throws Exception {
        var signPage = upload("contrato.docx", new byte[] {'P', 'K', 3, 4});

        mvc.perform(get(signPage))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("type=\"hidden\" name=\"format\" value=\"CADES\"")))
                .andExpect(content().string(not(containsString("value=\"PADES\""))));
    }

    @Test
    void showsFileWithoutSignatures() throws Exception {
        var signPage = upload("contrato.docx", new byte[] {'P', 'K', 3, 4});

        mvc.perform(get(signPage.replace("/sign", "")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("contrato.docx")))
                .andExpect(content().string(containsString("não contém assinaturas")));
    }

    @Test
    void showsPdfInBrowser() throws Exception {
        var signPage = mvc.perform(post("/documents/sample")).andReturn().getResponse().getRedirectedUrl();

        mvc.perform(get(signPage.replace("/sign", "/file")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("inline;")))
                .andExpect(header().string("Content-Disposition", containsString("documento-exemplo.pdf")));
    }

    @Test
    void downloadsOtherFilesInsteadOfRenderingThem() throws Exception {
        var signPage = upload("pagina.html", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        mvc.perform(get(signPage.replace("/sign", "/file")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void offersEncapsulatedContentOnlyForCadesSignatures() throws Exception {
        var signPage = mvc.perform(post("/documents/sample")).andReturn().getResponse().getRedirectedUrl();

        mvc.perform(get(signPage.replace("/sign", "/content")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsEmptyUpload() throws Exception {
        var empty = new MockMultipartFile("file", "vazio.pdf", "application/pdf", new byte[0]);

        mvc.perform(multipart("/documents").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"))
                .andExpect(content().string(containsString("<h1>Arquivo inválido</h1>")));
    }

    @Test
    void answersNotFoundForUnknownDocument() throws Exception {
        mvc.perform(get("/documents/" + UUID.randomUUID() + "/sign"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    private String upload(String name, byte[] content) throws Exception {
        return mvc.perform(multipart("/documents").file(new MockMultipartFile("file", name, null, content)))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }
}
