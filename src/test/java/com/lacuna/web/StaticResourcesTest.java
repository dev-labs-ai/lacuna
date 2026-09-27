package com.lacuna.web;

import com.lacuna.support.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Static files are linked by URLs holding a hash of their content, so a deploy that changes a script also changes its
 * URL, and no browser keeps running a cached copy of the old one.
 */
@SpringBootTest
@ImportTestcontainers(TestInfrastructure.class)
@AutoConfigureMockMvc
class StaticResourcesTest {

    private static final Pattern VERSIONED_APP_SCRIPT = Pattern.compile("/js/app-\\p{XDigit}{32}\\.js");

    @Autowired
    MockMvc mvc;

    @Test
    void linksScriptsByTheHashOfTheirContent() throws Exception {
        var page = mvc.perform(get("/batch"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/css/app-")))
                .andReturn().getResponse().getContentAsString();
        var script = VERSIONED_APP_SCRIPT.matcher(page);

        assertThat(script.find()).as("versioned app.js linked from /batch").isTrue();
        mvc.perform(get(script.group()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-max-file-size")));
    }
}
