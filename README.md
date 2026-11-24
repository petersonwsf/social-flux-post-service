# post_service

Serviço de **postagens** do Social Flux: postagens, respostas (postagem com pai), curtidas e a mídia das postagens (imagens
e GIFs). Spring Boot 4.1 · Java 21 · PostgreSQL próprio (banco `post`) · RabbitMQ (outbox/inbox via `libs/socialflux-events`)
· mídia via `libs/socialflux-media` (RustFS) · resource server JWT RS256 (JWKS do `authentication_service`). Porta **8084**.

Plano e decisões: [`../docs/plano-dominio-postagens-servico-proprio.md`](../docs/plano-dominio-postagens-servico-proprio.md)
(D1–D18; contratos no §16.5). Estrutura do código: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Regras

| Regra | Detalhe |
| --- | --- |
| Identidade | Sempre do `sub` do token. Nenhuma rota aceita autor, `id_usuario` ou id de curtida do cliente; `{id}` é só o alvo |
| Conteúdo | Texto (≤ `POST_MAX_BODY` = 1.000 caracteres, sem ser só espaços; bordas aparadas) **e/ou** anexos (até 5 imagens PNG/JPEG/WebP, ou um GIF sozinho); pelo menos um |
| Moderação | O **texto** e as **imagens** de postagens e respostas passam pela API de moderação da OpenAI antes de qualquer objeto ser gravado (o texto, antes até de processar as imagens; as imagens, depois do processamento e antes do `put`). Imagem: bloqueia pornografia (`sexual`), violência gráfica e automutilação; GIF é analisado pelo **primeiro quadro**; as imagens da postagem são consultadas em paralelo. Bloqueia ódio (`hate`, `hate/threatening`), ameaça ou desejo de morte a alguém (`harassment/threatening`), violência gráfica (`violence/graphic`) e automutilação (`self-harm*`); ofensa comum (`harassment`) e `violence` sozinho (pega brincadeira e notícia) passam. Recusa de qualquer imagem recusa a postagem inteira, com 422 e nada gravado. Desligada por padrão |
| Resposta | O pai precisa existir **e** ser visível para quem responde; senão 404 igual a "não existe" |
| Visibilidade (D6) | Dono, autor público ou aresta "eu sigo o autor" (cópia local de `follow.*`). Vale para postagem avulsa, feed, perfil, respostas, ancestrais, curtir e a imagem. Conta excluída = invisível |
| Respostas | Cada resposta é filtrada pela visibilidade do **seu** autor |
| Excluir (D7) | Só o autor (403 se a postagem é visível mas de outra pessoa; 404 se não existe/invisível). Apaga a subárvore e as curtidas; imagens apagadas **depois do commit** (a falta do arquivo nunca impede a exclusão) |
| Curtir | Idempotente; só em postagem visível; o contador só muda quando a curtida muda; pode curtir a própria postagem |
| Conta excluída (D12) | `profile.deleted.v1` marca o autor `DELETED` (conteúdo some na hora) e apaga as arestas; depois do commit o `AccountPurger` apaga postagens, respostas dependentes, curtidas dadas (ajustando contadores alheios) e a mídia, em transações curtas |
| Limites (D10) | `POST_RATE_POSTS_PER_HOUR` (60) e `POST_RATE_LIKES_PER_HOUR` (600, curtir + descurtir) por usuário → 429 `muitas_requisicoes` com `Retry-After` |
| Datas | `timestamptz`; a API devolve ISO-8601 em UTC |
| Concorrência | Curtir/descurtir/responder travam a linha da postagem (ou do pai); excluir trava o pai antes da postagem e a subárvore de pai para filho — a mesma ordem da cascata, sem impasse |

## API (`/api/postagens`, token de usuário)

| Rota | Sucesso | Erros |
| --- | --- | --- |
| `POST /api/postagens` (multipart: `legenda`, `imagens` (até 5, campo repetido; o antigo `imagem` continua aceito), `pai`) | 201 + postagem | 422 `postagem_vazia` / `imagens_demais` / `gif_deve_ficar_sozinho` / `texto_muito_longo` / `imagem_invalida` / `imagem_animada` / `gif_invalido` / `conteudo_proibido` / `conteudo_automutilacao` (mensagem de apoio com o CVV, 188), 413 `imagem_muito_grande`, 404 `postagem_nao_encontrada` (pai), 400 `parametro_invalido`, 429, 503 `perfil_indisponivel` / `servidor_ocupado` / `moderacao_indisponivel` (provedor de moderação fora do ar: imagens por padrão; texto só com `POST_MODERATION_FAIL_CLOSED_TEXT=true`) |
| `GET /api/postagens/{id}` | 200 | 404 `postagem_nao_encontrada` (inexistente, invisível ou de conta excluída: igual) |
| `DELETE /api/postagens/{id}` | 204 | 404; 403 `forbidden` (visível, mas de outra pessoa) |
| `GET /api/postagens/feed?cursor&limite` | 200 (só raízes, minhas e de quem eu sigo) | 400 `parametro_invalido` |
| `GET /api/postagens?autor&filtro&cursor&limite` | 200 | 404 `usuario_nao_encontrado`, 403 `conta_privada`, 400 |
| `GET /api/postagens/{id}/respostas?cursor&limite` | 200 | 404 se a postagem não é visível |
| `GET /api/postagens/{id}/ancestrais` | 200 (até 10 níveis, da mais antiga à mais próxima, parando no primeiro invisível) | 404 |
| `PUT` / `DELETE /api/postagens/{id}/curtida` | 200 `{ curtido, curtidas }` | 404 se não visível; 429 |
| `GET /media/posts/{chave}` | 200 (bytes) / 304 | 404 (chave desconhecida **ou** postagem invisível); 401 sem token |

Parâmetros: `limite` 1–100 (padrão 20); `filtro` ∈ `posts` (padrão: só raízes), `interacoes` (raízes + respostas do autor),
`midia` (só com anexo); `autor` = UUID da conta; `cursor` opaco (`created_at` + id, base64url).

**Postagem:**
```json
{
  "id": "0192f3c4-7a1e-7b2d-9c10-5e8f4a1b2c3d",
  "legenda": "texto",
  "imagem": { "url": "/media/posts/po_….jpg", "tipo": "IMAGE", "largura": 1200, "altura": 800 },
  "imagens": [ { "url": "/media/posts/po_….jpg", "tipo": "IMAGE", "largura": 1200, "altura": 800 } ],
  "criado_em": "2026-11-15T14:03:22.123456Z",
  "autor": { "id": "<uuid da conta>", "username": "ana", "nome": "Ana", "foto_perfil": "av_….jpg", "foto_url": "/media/avatars/av_….jpg", "privado": false },
  "pai": { "id": "…", "autor_username": "bia" },
  "curtidas": 3, "curtido": true, "respostas": 2
}
```
`legenda` pode ser `null`; `imagem` (a primeira) é `null` e `imagens` é `[]` sem anexo (nunca sem legenda e sem imagem ao mesmo tempo); `imagens` traz até 5 anexos em ordem, ou um GIF sozinho; `pai` é `null` em postagem raiz e `pai.autor_username` é `null` quando
quem pede não pode ver o pai. **Lista:** `{ "itens": [...], "proximoCursor": "…" | null }`. **Ancestrais:** `{ "itens": [...] }`.
Erros: `{ "mensagem", "sucesso": false, "codigo" }`.

## Mídia (D8, D9, D16)

- Até 5 anexos por postagem: todos são processados antes de gravar qualquer um (recusa = nada gravado) e um GIF só vale sozinho.
- Cada anexo é processado pela biblioteca **fora** da transação: PNG/JPEG/WebP → reduzido (lado maior ≤ 1.600 px), orientação EXIF
  aplicada, re-codificado em JPEG (ou PNG com transparência), sem metadados; GIF → validado e regravado (≤ 8 MB, ≤ 1.280 px,
  ≤ 300 quadros), sem redimensionar. PNG/WebP animados → 422 `imagem_animada`.
- Gravado no bucket `POST_MEDIA_BUCKET` com chave `po_<uuidv7>.<jpg|png|gif>`; se a transação falhar, o objeto é apagado.
- Entrega em `GET /media/posts/{chave}` (atrás do `forward_auth` do gateway): visibilidade conferida a cada pedido,
  `Cache-Control: private, max-age=31536000, immutable`, `ETag` = chave (304 sem ler o objeto, mas com a checagem),
  `X-Content-Type-Options: nosniff`, `Content-Type` fixo pela extensão. O navegador nunca fala com o RustFS.

## Eventos (RabbitMQ, exchange `socialflux.events`)

Publica (outbox na mesma transação; `aggregateId` = postagem, `aggregateVersion` = 1; só ids, datas e trecho de texto):

| Evento | Quando | `data` |
| --- | --- | --- |
| `post.created.v1` | postagem ou resposta criada | `postId, authorId, parentId \| null, parentAuthorId \| null, mediaKind (IMAGE\|GIF\|null), excerpt (≤ 120 caracteres), createdAt` |
| `post.deleted.v1` | excluída (um por postagem, inclusive em cascata e na exclusão de conta) | `postId, authorId` |
| `post.liked.v1` / `post.unliked.v1` | a curtida de fato mudou (inclui as curtidas removidas na exclusão de conta) | `postId, postAuthorId, userId` |

Consome (idempotente por inbox; tipos desconhecidos e `aggregateVersion` antigo ignorados):

| Fila | Ligação | Eventos |
| --- | --- | --- |
| `post-service.profile-events` | `profile.#` | `profile.created/updated/privacy-changed/deleted.v1` → `author_snapshot` (exclusão deixa túmulo e dispara a limpeza) |
| `post-service.follow-events` | `follow.#` | `follow.created.v1` / `follow.removed.v1` → `follow_edge` (só vale a aresta mais nova do par; `removed` deixa túmulo em `follow_removed` por 7 dias). Solicitações são ignoradas |

## Tabelas (`V1__post.sql`, `V3__post_images.sql`)

`author_snapshot` (cópia pública dos perfis), `follow_edge` e `follow_removed` (cópia das arestas e túmulos), `post`
(UUIDv7, `parent_id` com `on delete cascade`, `body ≤ 1000`, `image_key/kind/width/height`, `likes_count`, `replies_count`),
`post_image` (até 5 imagens por postagem, com `position`; a 1ª também fica em `post.image_*`), `post_like` (PK `(post_id, user_id)`), `outbox_event`, `processed_message`.

## Rotinas agendadas

| Rotina | Intervalo | O que faz |
| --- | --- | --- |
| `Maintenance` | `POST_MAINTENANCE_INTERVAL` (1 h; 1ª após 10 min) | poda túmulos com mais de `POST_TOMBSTONE_RETENTION` (7 dias); varre o bucket e apaga objetos `po_*` sem postagem mais velhos que `POST_MEDIA_ORPHAN_GRACE` (1 h); termina a limpeza de contas excluídas |
| `CounterReconciler` | `POST_RECONCILER_INTERVAL` (1 h; 1ª após 5 min) | recalcula `likes_count`/`replies_count` por `count(*)` (até `POST_RECONCILER_BATCH` por rodada) e corrige, registrando quantos divergiam |
| Relay/expurgo do outbox | biblioteca de eventos | publica os pendentes e expurga os publicados após 7 dias |

## Configuração (variáveis de ambiente)

| Variável | Padrão | Descrição |
| --- | --- | --- |
| `POST_DB_URL` / `POST_DB_NAME` | `jdbc:postgresql://localhost:5432/post` / `post` | Banco do serviço |
| `POST_DB_USER` / `POST_DB_PASSWORD` | `IDENTITY_DB_USER` / `IDENTITY_DB_PASSWORD` | Credenciais (mesmo role do auth, por padrão) |
| `AUTH_JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_CLOCK_SKEW` | iguais aos outros serviços | Validação do token de usuário |
| `RABBITMQ_HOST/PORT/USER/PASSWORD`, `EVENTS_EXCHANGE`, `POST_EVENTS_ENABLED`, `POST_OUTBOX_INTERVAL` | `localhost`… / `true` / `PT0.5S` | Broker e eventos |
| `POST_ALLOWED_ORIGINS` | vazio (= própria origem) | `OriginCheckFilter` |
| `POST_MAX_BODY` | `1000` | Tamanho máximo do texto (≤ 1000) |
| `POST_RATE_POSTS_PER_HOUR` / `POST_RATE_LIKES_PER_HOUR` | `60` / `600` | Limites por usuário |
| `POST_MEDIA_BUCKET` | `post-media` | Bucket das imagens (criado pelo `rustfs_init`) |
| `POST_MEDIA_STORAGE` | `s3` | `s3` ou `memory` (só testes) |
| `POST_MEDIA_MAX_CONCURRENT` | `4` | Processamentos de imagem simultâneos (sem vaga em ~5 s → 503 `servidor_ocupado`) |
| `POST_MEDIA_ORPHAN_GRACE` | `PT1H` | Idade mínima de um objeto sem postagem para ser apagado |
| `RUSTFS_ENDPOINT`, `RUSTFS_ACCESS_KEY`, `RUSTFS_SECRET_KEY`, `RUSTFS_REGION` | `http://localhost:9000`… | Armazenamento S3 compatível. Hoje a credencial **raiz** do RustFS (credencial por bucket pendente, D18) |
| `POST_MODERATION_ENABLED` | `false` | Liga a moderação de texto. Ligada, exige `POST_MODERATION_API_KEY` (falha ao subir sem ela) |
| `POST_MODERATION_API_KEY` | vazio | Chave da API da OpenAI. Segredo: só no `.env` (gitignored) |
| `POST_MODERATION_BASE_URL` / `POST_MODERATION_MODEL` / `POST_MODERATION_TIMEOUT` | `https://api.openai.com` / `omni-moderation-latest` / `PT3S` | Provedor, modelo e tempo máximo da chamada |
| `POST_MODERATION_FAIL_CLOSED_TEXT` | `false` | Provedor fora do ar: `false` deixa o texto passar (aviso no log); `true` recusa com 503 |
| `POST_MODERATION_FAIL_CLOSED_IMAGES` | `true` | Provedor fora do ar: `true` recusa as imagens com 503; `false` deixa passar (aviso no log) |
| `POST_MODERATION_HATE` / `_HATE_THREATENING` / `_HARASSMENT_THREATENING` / `_SEXUAL` / `_SELF_HARM` / `_SELF_HARM_IMAGE` / `_VIOLENCE_GRAPHIC` | `0.30` / `0.30` / `0.65` / `0.70` / `0.50` / `0.25` / `0.70` | Score (0 a 1) a partir do qual cada categoria bloqueia. Os scores não são probabilidades: valores iniciais calibrados com frases em português (ofensa comum fica com `hate` ≤ 0,18); ajuste com exemplos reais |
| `POST_MAINTENANCE_ENABLED` / `POST_MAINTENANCE_INTERVAL` / `POST_TOMBSTONE_RETENTION` / `POST_PURGE_BATCH` | `true` / `PT1H` / `P7D` / `200` | Rotinas de manutenção |
| `POST_RECONCILER_ENABLED` / `POST_RECONCILER_INTERVAL` / `POST_RECONCILER_BATCH` | `true` / `PT1H` / `500` | Reconciliador de contadores |

Multipart: `max-file-size` 8 MB / `max-request-size` 30 MB, para até 5 imagens de 5 MB (os limites finos ficam na biblioteca de mídia).

## Rodando e testando

```bash
scripts/dev/run-post.sh          # ou .ps1, na raiz: usa o .env, instala as bibliotecas e sobe na porta 8084
mvn -pl post_service -am test    # na raiz: PostgreSQL embutido, JWKS local, mídia em memória (sem Docker)
```

## Limites conhecidos

- **Moderação:** o modelo erra preconceito sutil (ex.: "mulher tem que lavar louça" fica com `hate` ≈ 0,18, abaixo do que separa ofensa comum) e racismo velado; só um classificador com prompt próprio pegaria. O provedor **não avalia ódio em imagem** (nem texto dentro da imagem); o limiar `sexual` ainda não foi calibrado com
  imagens reais; o de automutilação em imagem (0,25) é baixo de propósito e pode barrar fotos de cicatrizes antigas, tatuagens
  ou arte corporal (falso positivo); GIF só é analisado pelo primeiro quadro (um conteúdo proibido só em quadros posteriores passa); postagens
  antigas, avatares e bio não são revisados. O texto e as imagens das postagens (inclusive de contas privadas) são enviados à OpenAI quando ligada: mencione isso na política de privacidade. O texto
  analisado nunca vai para o log (só a categoria que bloqueou). Docker Compose ainda não repassa as variáveis `POST_MODERATION_*`
  ao container (rodando o serviço no host, elas vêm do `.env`).

- A **revogação imediata** do token (logout geral, troca de senha) é aplicada pelo gateway (`forward_auth`); o serviço só valida
  assinatura e validade do JWT. Não o exponha sem o gateway.
- Consistência eventual da privacidade (§4 do plano): entre seguir/deixar de seguir/mudar a privacidade e o evento chegar, a
  visibilidade pode estar um passo atrás (ms a segundos). Sem bootstrap de usuários/arestas anteriores ao serviço.
- Rate limit em memória (uma instância). A entrega de imagens passa pelo serviço (sem URLs assinadas).
- Uma resposta criada no exato instante em que um ancestral é excluído pode ficar sem o seu `post.deleted.v1` (a postagem e a
  imagem saem do mesmo jeito: cascata e varredura de órfãos).
- Credencial raiz do RustFS; teste contra o RustFS real ainda não executado.
