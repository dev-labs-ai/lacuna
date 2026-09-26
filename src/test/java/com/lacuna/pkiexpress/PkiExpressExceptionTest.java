package com.lacuna.pkiexpress;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PkiExpressExceptionTest {

    @Test
    void keepsOnlyTheExplanationOfPkiExpressOutput() {
        var output = """
                The provided file was not found
                   at Lacuna.PkiExpress.Commands.CompleteSigCommand.Execute(List`1 arguments)
                   at Lacuna.PkiExpress.Commands.Command`2.Run(IEnumerable`1 options)
                """;

        var exception = PkiExpressException.from(new RuntimeException(output));

        assertThat(exception.getMessage()).isEqualTo("The provided file was not found");
        assertThat(exception.details()).isNull();
    }

    @Test
    void explainsFilesThatAreNotValidPdfs() {
        var output = """
                FATAL: Lacuna.Pki.InvalidPdfException: Rebuild failed: trailer not found.; Original message: PDF startxref not found.
                 ---> Lacuna.T8.text.exceptions.InvalidPdfException: Rebuild failed: trailer not found.
                   --- End of inner exception stack trace ---
                   at Lacuna.Pki.Pades.PadesDocument.Open(Byte[] content)
                """;

        var exception = PkiExpressException.from(new RuntimeException(output));

        assertThat(exception.getMessage()).isEqualTo("O arquivo não é um PDF válido ou está corrompido.");
        assertThat(exception.details()).isEqualTo(
                "FATAL: Lacuna.Pki.InvalidPdfException: Rebuild failed: trailer not found.; Original message: PDF startxref not found.");
    }

    @Test
    void keepsItsOwnExceptionsAsTheyAre() {
        var exception = new PkiExpressException("mensagem", "relatório");

        assertThat(PkiExpressException.from(exception)).isSameAs(exception);
    }
}
