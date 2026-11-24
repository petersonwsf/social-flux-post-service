# Arquitetura — post_service

Serviço de postagens (postagens, respostas, curtidas e mídia). Camadas: `web` (controllers, DTOs, erros) → `service` (regras e
mecanismos transacionais) → `domain` (repositórios JDBC e registros). Rotas, eventos e configuração: [`../README.md`](../README.md).

```
gateway (forward_auth) ─► SecurityConfig (JWT RS256, OriginCheckFilter)
  ├─► PostController ─► PostService ─┬─► PostRepository / AuthorRepository (PostgreSQL `post`)
  │                   └► LikeService ─┤
  │                                   └─► PostWriter (mesma transação: linhas + contadores + Outbox) ─► PostMediaService.deleteAfterCommit
  ├─► MediaController ─► PostMediaService ─► MediaProcessor / MediaStorage (libs/socialflux-media → RustFS `post-media`)
RabbitMQ ─► EventListeners ─► EventHandlers (Inbox) ─┬─► ProfileSyncService ─► author_snapshot (+ AccountPurger após o commit)
                                                     └─► FollowSyncService ─► follow_edge / follow_removed
@Scheduled ─► Maintenance (túmulos, órfãos, contas excluídas) · CounterReconciler (contadores)
```

## Árvore de diretórios

```
post_service/
├── pom.xml                       parent spring-boot-starter-parent 4.1.2; socialflux-events e socialflux-media; testes com embedded-postgres
├── Dockerfile                    build com contexto na raiz (instala as duas bibliotecas), JRE 21 alpine, usuário sem privilégios
├── .gitignore                    exclui target/ e arquivos de IDE
├── README.md                     regras, API, mídia, eventos, tabelas, rotinas, configuração
├── docs/ARCHITECTURE.md          este arquivo
└── src/
    ├── main/java/com/socialflux/post/
    │   ├── config/               propriedades e relógio
    │   ├── security/             resource server JWT e defesa CSRF
    │   ├── moderation/           moderação de conteúdo (texto): moderador, política de scores e porta de entrada para o serviço
    │   ├── web/                  controllers, DTOs e erros
    │   ├── service/              regras, escrita transacional, mídia, sincronização, rotinas
    │   ├── domain/               repositórios JDBC e registros
    │   └── events/               filas, listeners e tratadores de eventos consumidos
    ├── main/resources/
    │   ├── application.yml       datasource, multipart (8/9 MB), Tomcat (swallow 16 MB), RabbitMQ, socialflux.events/media e bloco `post.*`
    │   └── db/migration/        V1__post.sql, V2__remove_legacy_id.sql (remove `author_snapshot.legacy_id`) e V3__post_images.sql (`post_image`: até 5 imagens por postagem)
    └── test/java/com/socialflux/post/
```

## Arquivos (`com.socialflux.post`)

| Arquivo | O que representa |
| --- | --- |
| `PostServiceApplication.java` | `main`, `@ConfigurationPropertiesScan`, `@EnableScheduling` |

### `config/`

| Arquivo | O que representa |
| --- | --- |
| `PostProperties.java` | Configuração `post.*` validada ao subir: segurança (JWKS, issuer, audiência), origens, limites (texto, postagens/h, curtidas/h), mídia (bucket, carência de órfãos), manutenção e reconciliador |
| `ClockConfig.java` | `Clock` UTC injetável |

### `security/`

| Arquivo | O que representa |
| --- | --- |
| `SecurityConfig.java` | Nega tudo por padrão; `/api/**` e `/media/posts/**` exigem token de usuário (`ROLE_USER`); `/actuator/health` público; respostas 401/403 no formato de erro |
| `JwtDecoderConfig.java` | Decoder RS256 pelo JWKS do auth, com validação de expiração, issuer e audiência |
| `JwtAuthorities.java` | Só token de usuário (`sub` UUID) ganha `ROLE_*`; token de serviço fica sem autoridade |
| `OriginCheckFilter.java` | Defesa CSRF em métodos não seguros (`Sec-Fetch-Site` e `Origin`) |

### `moderation/`

| Arquivo | O que representa |
| --- | --- |
| `ContentGuard.java` | Usado por `PostService.create`: consulta o moderador e traduz o resultado em 422 `conteudo_proibido` / `conteudo_automutilacao`; se o provedor falhar, libera ou recusa com 503 conforme `fail-closed-text`. Nunca loga o texto |
| `ContentModerator.java` | Interface: `checkText(texto)` devolve um `Verdict` ou lança `ModerationUnavailableException` |
| `OpenAiContentModerator.java` | Chama `POST /v1/moderations` (`RestClient`, timeout curto) e lê só os scores das categorias usadas |
| `NoopContentModerator.java` | Moderador desligado: aprova tudo |
| `ModerationPolicy.java` | Regras puras sobre os scores (limiares de `post.moderation.thresholds`); automutilação tem prioridade |
| `Verdict.java` / `ModerationUnavailableException.java` | Resultado (`ALLOWED`, `FORBIDDEN`, `SELF_HARM`, com a categoria só para log) e falha do provedor |
| `ModerationConfig.java` | Escolhe o moderador; ligado sem `POST_MODERATION_API_KEY` falha ao subir |

### `web/`

| Arquivo | O que representa |
| --- | --- |
| `PostController.java` | `/api/postagens`: criar (multipart), ler, excluir, feed, lista do autor, respostas, ancestrais, curtir/descurtir; quem age vem do token; id malformado = 404 |
| `MediaController.java` | `GET /media/posts/{chave}`: bytes com tipo fixo, `nosniff`, cache privado imutável, `ETag`/304 |
| `Dtos.java` | Corpos: `Post` (com `Author`, `Image` e a lista `imagens`, `Parent`), `PageView`, `AncestorsView`, `LikeView` |
| `MediaErrors.java` | Tradução de `MediaException` para `imagem_invalida`, `imagem_animada`, `gif_invalido`, `imagem_muito_grande`, `servidor_ocupado` |
| `ApiError.java` / `ApiException.java` | Corpo `{ mensagem, sucesso, codigo }` e erro de negócio com status (inclui 404 uniforme de postagem e 429 com `Retry-After`) |
| `GlobalExceptionHandler.java` | Converte exceções (negócio, mídia, upload grande, parâmetros, métodos) no corpo padrão; 500 genérico com log |

### `service/`

| Arquivo | O que representa |
| --- | --- |
| `PostService.java` | Regras: criar (valida, processa o anexo fora da transação, transação curta com o pai travado, compensação do objeto), ler, feed (minhas postagens e de quem sigo), lista do autor (403 `conta_privada`), respostas, ancestrais, excluir (trava pai → postagem; 403/404) |
| `LikeService.java` | Curtir/descurtir idempotentes com a postagem travada e limite por hora |
| `PostWriter.java` | Mecanismos na transação do chamador (`MANDATORY`): inserir + contador do pai + `post.created.v1`; excluir subárvore + contador + `post.deleted.v1` por nó + remoção de imagens após o commit; curtir/descurtir + contador + evento |
| `PostMediaService.java` | Processa e grava os anexos (até 5; GIF só sozinho), compensação, remoção após o commit, entrega com checagem de visibilidade e 304, varredura de órfãos |
| `ProfileSyncService.java` | Aplica `profile.*` em `author_snapshot` (versão, túmulo); exclusão marca `DELETED`, apaga arestas e agenda o `AccountPurger` |
| `FollowSyncService.java` | Aplica `follow.created/removed.v1` em `follow_edge`/`follow_removed` (fora de ordem tolerado) |
| `AccountPurger.java` | Limpeza de conta excluída em transações curtas `REQUIRES_NEW`: postagens (subárvores), curtidas dadas, arestas |
| `Maintenance.java` | Rotina agendada: poda de túmulos, varredura de mídia órfã, limpeza de contas excluídas pendentes |
| `CounterReconciler.java` | Rotina agendada que recalcula `likes_count`/`replies_count` e corrige divergências |
| `RateLimiter.java` | Janela deslizante em memória (postagens e curtidas por hora) |

### `domain/`

| Arquivo | O que representa |
| --- | --- |
| `PostRepository.java` | Consultas com o fragmento de visibilidade em SQL: postagem visível, feed, lista do autor (filtros), respostas, ancestrais (CTE recursiva), mídia por chave; escrita: travas (`for update of p`), inserção, contadores, subárvore travada pai → filho, exclusão; curtidas; órfãos; reconciliação |
| `PostView.java` | Postagem como o viewer a vê (autor, contadores, "curti", resumo do pai, capa e `images`) |
| `PostImage.java` | Uma imagem da postagem (chave, tipo, dimensões, posição 0–4) |
| `AuthorRepository.java` | `author_snapshot`: leitura (com `for share`/`for update`), "segue?", inserção/atualização, contas excluídas com sobras |
| `AuthorSnapshot.java` | Cópia pública do perfil de um autor (`ACTIVE`/`DELETED`) |
| `FollowEdgeRepository.java` | `follow_edge` (upsert pela aresta mais nova, remoção só do mesmo vínculo) e `follow_removed` (túmulos, poda) |
| `Cursor.java` | Cursor opaco `(created_at, id)` em base64url; adulterado = 400 |
| `UserRef.java` | Autor na URL: o UUID da conta |

### `events/`

| Arquivo | O que representa |
| --- | --- |
| `Amqp.java` | Filas `post-service.profile-events` (`profile.#`) e `post-service.follow-events` (`follow.#`), com DLQ da biblioteca |
| `EventListeners.java` | `@RabbitListener` das duas filas (só com `socialflux.events.enabled=true`) |
| `EventHandlers.java` | Envelope + inbox + delegação para `ProfileSyncService`/`FollowSyncService` (usado direto nos testes) |

## Testes (`src/test`)

| Arquivo | O que cobre |
| --- | --- |
| `support/AbstractPostTest.java` | Sobe o serviço com PostgreSQL embutido, JWKS local e mídia em memória; usuários e arestas por eventos; atalhos HTTP; geração de PNG/GIF; invariante dos contadores |
| `support/Http.java` / `support/TestSupportConfig.java` | Cliente HTTP (JSON e multipart) e publicador de eventos em memória (simula broker fora do ar) |
| `SecurityTest.java` | Token ausente/adulterado/expirado/audiência errada/de serviço, CSRF, health público |
| `SyncTest.java` | Eventos de perfil duplicados/antigos/desconhecidos, túmulo, arestas fora de ordem, exclusão de conta completa |
| `PostFlowTest.java` | Contrato da postagem, regras de conteúdo, só imagem (LOG-08), autor desconhecido, respostas, pai invisível, exclusão (403/404/cascata, LOG-03, LOG-05), ancestrais |
| `VisibilityTest.java` | Matriz dono/público/seguidor/estranho/excluído em postagem, perfil, feed, respostas, ancestrais, curtir e imagem; mudança de privacidade e deixar de seguir |
| `PaginationTest.java` | Cursor estável com empates, itens novos no meio, filtros, parâmetros inválidos |
| `LikeTest.java` | Idempotência, "curti", eventos só com mudança, 404 |
| `ConcurrencyTest.java` | Curtir × descurtir, curtir × excluir, responder × excluir o pai, respostas simultâneas, exclusões simultâneas na mesma árvore |
| `MediaTest.java` | Re-codificação e redução, entrega com cabeçalhos e 304, revalidação confere visibilidade, GIF intacto, recusas com códigos, exclusão da árvore apaga objetos, chaves inválidas, varredura de órfãos |
| `EventsTest.java` | Payloads `post.*` sem dado pessoal, um `deleted` por nó, broker fora do ar, reconciliador, manutenção |
| `ContentModerationTest.java` | Moderação na criação (moderador falso): texto limpo passa, ódio e automutilação recusados sem gravar nada, respostas também, postagem só com imagem não é analisada |
| `moderation/ModerationPolicyTest.java` / `ContentGuardTest.java` | Limiares e prioridades da política; erros 422/503 e política de falha do provedor (sem Spring) |
| `moderation/OpenAiContentModeratorTest.java` | Cliente contra um servidor HTTP local: cabeçalho e corpo, scores → veredito, erro HTTP, resposta ilegível e timeout |
| `LimitsTest.java` | 429 de postagens e curtidas com `Retry-After`, tamanho do texto configurável |

## `.github/workflows/`

| Arquivo | O que faz |
| --- | --- |
| `ci.yml` | CI em cada pull request: lint e, só se passar, testes |
| `cd.yml` | CD após o merge na `main`: repete os testes, publica a imagem no GHCR e atualiza o container na VPS por SSH (secrets listados no `docs/ARCHITECTURE.md` da raiz) |
