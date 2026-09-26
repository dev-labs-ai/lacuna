# Assinador Digital — Lacuna PKI Express + Web PKI

Aplicação Spring Boot (Thymeleaf) que assina e valida arquivos nos padrões PAdES e CAdES. A chave privada nunca sai
do computador do usuário: o navegador assina com o
[Web PKI](https://docs.lacunasoftware.com/articles/web-pki/get-started) e o servidor monta o arquivo assinado com o
[PKI Express](https://docs.lacunasoftware.com/articles/pki-express/java).

| Arquivo enviado | Formatos de assinatura | Resultado |
|---|---|---|
| PDF | PAdES ou CAdES, à escolha do usuário | PAdES: o próprio PDF, assinado. CAdES: `nome.pdf.p7s` contendo o PDF |
| Assinatura CAdES (`.p7s`) | CAdES (coassinatura) | O mesmo `.p7s` com mais uma assinatura |
| Qualquer outro arquivo | CAdES | `nome.ext.p7s` contendo o arquivo |

O tipo do arquivo é detectado pelo conteúdo, não pela extensão. O validador (`/validate`) aceita PDFs e `.p7s`,
inclusive assinaturas CAdES destacadas, que exigem também o arquivo original.

A assinatura em lote (`/batch`) assina até 20 arquivos de uma vez: o usuário escolhe o certificado e autoriza todas
as assinaturas uma única vez no Web PKI (`preauthorizeSignatures`), inclusive o PIN de tokens. A página então assina
cada arquivo pela API JSON (`/api/documents/{id}/signature/start` e `/complete`) e mostra o resultado de cada um; os
que falharem podem ser reenviados.

## Pré-requisitos

- Java 25
- PKI Express instalado e com licença ativada no servidor, com `pkie` no `PATH`
  ([instalação](https://docs.lacunasoftware.com/articles/pki-express/setup)). Em outra pasta, configure
  `lacuna.pki-express.home`.
- Web PKI (extensão + componente nativo) no navegador do usuário. Sem ele, a página de assinatura oferece a instalação.

## Executar

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Acesse http://localhost:8080. Sem licença, o Web PKI só funciona em `localhost`.

O perfil `dev` aceita certificados da PKI de teste da Lacuna e não valida o certificado ao escolhê-lo (a validação
fica só na conclusão, feita pelo PKI Express). Para testar sem um certificado ICP-Brasil, importe
`src/test/resources/pierre-de-fermat.pfx` (senha `1234`) no repositório de certificados do navegador
(Chrome: Configurações → Privacidade e segurança → Segurança → Gerenciar certificados).

## Fluxo de assinatura

| Etapa | Onde | O que acontece |
|---|---|---|
| 1 | `sign.html` + `signature.js` | O usuário escolhe o formato (se for PDF) e o certificado, que o Web PKI lê e envia ao servidor |
| 2 | `POST /documents/{id}/sign/start` | `CertificateValidator` recusa certificados não aceitos; `PadesSignatureStarter` ou `CadesSignatureStarter` calcula o hash a assinar e grava o arquivo de transferência |
| 3 | `sign-complete.html` + `signature.js` | O Web PKI assina o hash com a chave privada do usuário |
| 4 | `POST /documents/{id}/sign/complete` | `SignatureFinisher` insere a assinatura e grava o arquivo assinado como um novo documento |
| 5 | `GET /documents/{id}` | `PadesSignatureExplorer` ou `CadesSignatureExplorer` lista e valida as assinaturas |

| Classe | Responsabilidade |
|---|---|
| `PkiExpressOperators` | Cria os operadores do PKI Express já configurados (confiança, políticas, idioma, fuso) |
| `SignatureService` | Início e conclusão de assinaturas PAdES e CAdES |
| `SignatureApiController` | Versão JSON do fluxo de assinatura, usada pela assinatura em lote |
| `ValidationService` | Validação de PDFs e `.p7s` e extração do arquivo contido num `.p7s` |
| `DocumentStorage` | Armazenamento dos arquivos e seus metadados (nome, formato) |

## Configuração

| Propriedade | Padrão | |
|---|---|---|
| `lacuna.pki-express.home` | `pkie` do `PATH` | Pasta do executável `pkie` |
| `lacuna.pki-express.trust-lacuna-test-root` | `false` (`true` no perfil `dev`) | Aceita a PKI de teste da Lacuna. **Nunca em produção** |
| `lacuna.pki-express.trusted-roots` | — | Raízes adicionais (`.cer`) |
| `lacuna.pki-express.pades-policy` | `PadesBasicWithLTV` | Política PAdES (`StandardSignaturePolicies`) |
| `lacuna.pki-express.cades-policy` | `PkiBrazilCadesAdrBasica` | Política CAdES (ICP-Brasil AD-RB) |
| `lacuna.pki-express.culture` / `time-zone` | `pt-BR` / `America/Sao_Paulo` | Idioma das mensagens e data do carimbo visual |
| `lacuna.pki-express.offline` | `false` | Não consulta LCR/OCSP |
| `lacuna.signature.validate-certificate-on-selection` | `true` (`false` no perfil `dev`) | Valida o certificado escolhido antes de pedir a assinatura |
| `lacuna.web-pki.license` | — | Licença do Web PKI (Base64 ou JSON), necessária fora de `localhost` |
| `lacuna.storage.dir` | `<java.io.tmpdir>/lacuna` | Documentos e arquivos de trabalho do PKI Express |

As políticas padrão são as mesmas do PKI Express.

## Testes

```bash
./mvnw test
```

`SignatureFlowTest` e `ValidationControllerTest` executam os fluxos contra o PKI Express local, fazendo em Java o
papel do Web PKI com o certificado de teste (`TestSigner`). Eles são ignorados quando `pkie` não está no `PATH`.

## Antes de ir para produção

- Configure a licença do Web PKI e mantenha `trust-lacuna-test-root=false`.
- Troque o armazenamento em disco (`DocumentStorage`) pelo da sua aplicação e adicione autenticação/CSRF.
- Arquivos de transferência de assinaturas abandonadas ficam em `<storage>/pkie-transfer`; remova-os periodicamente.
