package com.lacuna.signature;

import com.lacuna.document.StoredDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Batch pages; the signatures themselves go through the API, see {@link SignatureApiTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchSignatureControllerTest {

    private static final byte[] PDF = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TEXT = "texto".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path storageDir;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("lacuna.storage.dir", storageDir::toString);
    }

    @Test
    void rendersUploadForm() throws Exception {
        mvc.perform(get("/batch"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"files\" multiple")))
                .andExpect(content().string(containsString("Até 20 arquivos por vez.")));
    }

    @Test
    void storesTheFilesAndOpensTheSigningPage() throws Exception {
        var signPage = mvc.perform(upload(file("contrato.pdf", PDF), file("nota.txt", TEXT), file("vazio.txt", new byte[0])))
                .andExpect(redirectedUrlPattern("/batch/sign?documents=*&documents=*"))
                .andReturn().getResponse().getRedirectedUrl();

        var documents = documentsAt(signPage);

        assertThat(documents).extracting(StoredDocument::name).containsExactly("contrato.pdf", "nota.txt");
    }

    @Test
    void offersPdfFormatsWhenTheBatchHasPdfs() throws Exception {
        var signPage = uploadBatch(file("contrato.pdf", PDF), file("nota.txt", TEXT));

        mvc.perform(get(signPage))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasPdf", true))
                .andExpect(content().string(containsString("/js/batch-signature.js")))
                .andExpect(content().string(containsString("name=\"pdfFormat\" value=\"PADES\" checked")))
                .andExpect(content().string(containsString("data-format=\"PDF\"")))
                .andExpect(content().string(containsString("data-format=\"OTHER\"")))
                .andExpect(content().string(containsString("Assinar 2 arquivos")));
    }

    @Test
    void omitsPdfFormatsWhenTheBatchHasNoPdf() throws Exception {
        var signPage = uploadBatch(file("nota.txt", TEXT));

        mvc.perform(get(signPage))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasPdf", false))
                .andExpect(content().string(not(containsString("name=\"pdfFormat\""))))
                .andExpect(content().string(containsString("Assinar 1 arquivo")));
    }

    @Test
    void rejectsBatchWithoutFiles() throws Exception {
        mvc.perform(upload(file("", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("backUrl", "/batch"));
    }

    @Test
    void rejectsMoreFilesThanTheLimit() throws Exception {
        var files = IntStream.rangeClosed(1, BatchSignatureController.MAX_FILES + 1)
                .mapToObj(i -> file("nota" + i + ".txt", TEXT))
                .toArray(MockMultipartFile[]::new);

        mvc.perform(upload(files))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("no máximo 20 arquivos")));
    }

    @Test
    void answersNotFoundForUnknownDocument() throws Exception {
        mvc.perform(get("/batch/sign").param("documents", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    private String uploadBatch(MockMultipartFile... files) throws Exception {
        return mvc.perform(upload(files))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    @SuppressWarnings("unchecked")
    private List<StoredDocument> documentsAt(String signPage) throws Exception {
        return (List<StoredDocument>) mvc.perform(get(signPage))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("documents");
    }

    private static MockMultipartHttpServletRequestBuilder upload(MockMultipartFile... files) {
        var request = multipart("/batch");
        for (var file : files) {
            request.file(file);
        }
        return request;
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, null, content);
    }
}
