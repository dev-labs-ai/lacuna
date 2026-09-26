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
- PostgreSQL e um armazenamento S3 (MinIO): o `compose.yaml` sobe os dois com Docker.

## Executar

```bash
docker compose up -d postgres minio
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Acesse http://localhost:8080. Sem licença, o Web PKI só funciona em `localhost`.

O perfil `dev` aceita certificados da PKI de teste da Lacuna e não valida o certificado ao escolhê-lo (a validação
fica só na conclusão, feita pelo PKI Express). Para testar sem um certificado ICP-Brasil, importe
`src/test/resources/pierre-de-fermat.pfx` (senha `1234`) no repositório de certificados do navegador
(Chrome: Configurações → Privacidade e segurança → Segurança → Gerenciar certificados).

### Com Docker

Só é preciso Docker e a licença do PKI Express na raiz do projeto, em `LacunaPkiLicense.config` (fora do git). A de
avaliação vem dos exemplos da Lacuna (veja [Renovar a licença](#renovar-a-licença)):

```bash
curl -fsSL -o LacunaPkiLicense.config \
  https://raw.githubusercontent.com/LacunaSoftware/PkiSuiteSamples/master/java/springmvc/LacunaPkiLicense.config
docker compose up --build
```

Acesse http://localhost:8080. O compose sobe também o PostgreSQL e o MinIO (console em http://localhost:9001, usuário
`lacuna`, senha `lacuna-secret`; credenciais só de desenvolvimento) e usa o perfil `dev`
(`SPRING_PROFILES_ACTIVE=default docker compose up` roda sem ele). As propriedades da [configuração](#configuração)
também podem ser passadas como variáveis de ambiente, por exemplo `LACUNA_WEB_PKI_LICENSE`.

O MinIO não publica mais imagens Docker (o repositório foi arquivado em abril de 2026); o compose usa a que a
Chainguard compila do código do MinIO, `cgr.dev/chainguard/minio`, gratuita só na tag `latest`. Como o app fala S3
padrão, trocar de servidor é só mudar o `compose.yaml`.

A imagem já traz o PKI Express (versão em `PKIE_VERSION`, no `Dockerfile`), mas não a licença: o PKI Express é ativado
quando o container inicia (`docker/entrypoint.sh`). A ativação fica presa ao hostname e aos endereços MAC, que o
Docker troca a cada container novo. Por isso o `compose.yaml` fixa os dois e guarda `/etc/pkie` no volume `pkie`, e a
ativação só é refeita quando eles mudam, quando o volume é apagado (`docker compose down -v`) ou quando o arquivo traz
outra licença. Os dados ficam nos volumes `postgres` e `minio`.

O build da imagem não roda os testes, porque eles precisam de um `pkie` ativado. Rode `./mvnw test` na máquina.

#### Renovar a licença

A licença de avaliação dos exemplos da Lacuna
([PkiSuiteSamples](https://github.com/LacunaSoftware/PkiSuiteSamples)) é renovada no começo de cada mês e vale por
cerca de dois meses. O container informa no log quando a licença ativada expira:

```bash
docker compose logs app | grep expires
```

Para renovar, antes ou depois de ela expirar, baixe a licença nova no lugar da atual (na raiz do projeto) e reinicie o
container:

```bash
curl -fsSL -o LacunaPkiLicense.config \
  https://raw.githubusercontent.com/LacunaSoftware/PkiSuiteSamples/master/java/springmvc/LacunaPkiLicense.config
docker compose restart
```

Ao iniciar, o container percebe que o arquivo traz outra licença e a ativa pela internet. Se a ativação falhar, ele
para e mostra a mensagem do PKI Express (`docker compose logs app`).

## Fluxo de assinatura

| Etapa | Onde | O que acontece |
|---|---|---|
| 1 | `sign.html` + `signature.js` | O usuário escolhe o formato (se for PDF) e o certificado, que o Web PKI lê e envia ao servidor |
| 2 | `POST /documents/{id}/sign/start` | `CertificateValidator` recusa certificados não aceitos; `PadesSignatureStarter` ou `CadesSignatureStarter` calcula o hash a assinar e grava o arquivo de transferência |
| 3 | `sign-complete.html` + `signature.js` | O Web PKI assina o hash com a chave privada do usuário |
| 4 | `POST /documents/{id}/sign/complete` | `SignatureFinisher` insere a assinatura e grava o arquivo assinado como um novo documento |
| 5 | `GET /documents/{id}` | `PadesSignatureExplorer` ou `CadesSignatureExplorer` lista e valida as assinaturas |

## Armazenamento

Cada documento, enviado ou assinado, é um objeto no bucket (`lacuna.storage.bucket`) e uma linha na tabela
`document` do PostgreSQL (migrações do Flyway em `src/main/resources/db/migration`):

| Coluna | |
|---|---|
| `id` | UUID versão 7, que também dá nome ao objeto |
| `bucket`, `object_key` | Onde está o objeto: `AAAA/MM/DD/<id>.<extensão>`, com a data (UTC) em que foi guardado, por exemplo `2026/09/26/01a0dec1-1590-7bd0-ab56-8d349e958db8.pdf` |
| `file_name` | Nome original, usado na tela e nos downloads |
| `size_bytes`, `mime_type` | Tamanho e tipo: `application/pdf`, `application/pkcs7-signature` (`.p7s`) ou `application/octet-stream` |
| `signed_at` | Data e hora (UTC) da assinatura que gerou o arquivo, lida do próprio arquivo assinado; vazia nos arquivos enviados |

O UUID versão 7 começa pelo instante de criação em milissegundos e termina com 74 bits aleatórios: os ids crescem com
o tempo, então entram no fim do índice da chave primária (o v4, aleatório, espalha as inserções pelo índice) e a
listagem de um dia no bucket sai em ordem de gravação. O app gera o id antes de gravar o objeto, cujo nome o contém;
o Java 25 ainda não gera v7 (o Java 26 tem `UUID.ofEpochMillis`), por isso a classe `UuidV7`.

O PKI Express trabalha com arquivos locais: o app baixa uma cópia do documento para `lacuna.storage.dir` enquanto o
PKI Express trabalha e a apaga em seguida.

| Classe | Responsabilidade |
|---|---|
| `PkiExpressOperators` | Cria os operadores do PKI Express já configurados (confiança, políticas, idioma, fuso) |
| `SignatureService` | Início e conclusão de assinaturas PAdES e CAdES |
| `SignatureApiController` | Versão JSON do fluxo de assinatura, usada pela assinatura em lote |
| `ValidationService` | Validação de PDFs e `.p7s` e extração do arquivo contido num `.p7s` |
| `DocumentStorage` | Documentos no S3 com seus dados na tabela `document` (via `DocumentRepository`) |

## Configuração

| Propriedade | Padrão | |
|---|---|---|
| `lacuna.pki-express.home` | `pkie` do `PATH` | Pasta do executável `pkie` |
| `lacuna.pki-express.trust-lacuna-test-root` | `false` (`true` no perfil `dev`) | Aceita a PKI de teste da Lacuna. **Nunca em produção** |
| `lacuna.pki-express.trusted-roots` | — | Raízes adicionais (`.cer`) |
| `lacuna.pki-express.pades-policy` | `PadesBasicWithLTV` | Política PAdES (`StandardSignaturePolicies`) |
| `lacuna.pki-express.cades-policy` | `PkiBrazilCadesAdrBasica` | Política CAdES (ICP-Brasil AD-RB) |
| `lacuna.pki-express.culture` | `pt-BR` | Idioma das mensagens e do carimbo visual |
| `lacuna.pki-express.offline` | `false` | Não consulta LCR/OCSP |
| `lacuna.signature.validate-certificate-on-selection` | `true` (`false` no perfil `dev`) | Valida o certificado escolhido antes de pedir a assinatura |
| `lacuna.web-pki.license` | — | Licença do Web PKI (Base64 ou JSON), necessária fora de `localhost` |
| `spring.datasource.url` / `username` / `password` | — (PostgreSQL do compose no perfil `dev`) | Banco de dados |
| `lacuna.storage.bucket` | `documents` | Bucket dos documentos, criado na inicialização se não existir |
| `lacuna.storage.s3.endpoint` | — (AWS S3; MinIO do compose no perfil `dev`) | Servidor compatível com S3 |
| `lacuna.storage.s3.region` | `us-east-1` | Região S3 |
| `lacuna.storage.s3.access-key` / `secret-key` | — (cadeia padrão da AWS) | Credenciais S3 |
| `lacuna.storage.dir` | `<java.io.tmpdir>/lacuna` | Arquivos de trabalho do PKI Express e cópias temporárias dos documentos |

As políticas padrão são as mesmas do PKI Express. A data da assinatura sai em UTC no carimbo do PDF, que não tem como
saber onde será lido, e no fuso do navegador na página do documento.

## Testes

```bash
./mvnw test
```

Os testes precisam de Docker: o Testcontainers sobe PostgreSQL e MinIO com as mesmas imagens do compose
(`TestInfrastructure`). `SignatureFlowTest` e `ValidationControllerTest` executam os fluxos contra o PKI Express
local, fazendo em Java o papel do Web PKI com o certificado de teste (`TestSigner`). Eles são ignorados quando `pkie`
não está no `PATH`.

## Antes de ir para produção

- Configure a licença do Web PKI e mantenha `trust-lacuna-test-root=false`.
- Adicione autenticação/CSRF: hoje quem tem o link de um documento o acessa.
- Passe as credenciais do banco e do S3 por variáveis de ambiente ou secrets, e crie o bucket com a sua
  infraestrutura; o app só o cria quando não existe.
- Arquivos de transferência de assinaturas abandonadas ficam em `<storage>/pkie-transfer`; remova-os periodicamente.
