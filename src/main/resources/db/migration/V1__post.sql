-- Esquema do post_service (PostgreSQL). Banco próprio do serviço; nada aqui referencia outro banco.

-- Cópia local, somente leitura, do perfil PÚBLICO dos autores (alimentada pelos eventos profile.*). Nenhum dado pessoal.
create table author_snapshot (
    user_id          uuid         primary key,              -- = id do perfil = `sub` do JWT
    legacy_id        integer      unique,                   -- TEMPORÁRIO: o frontend ainda usa o id inteiro
    username         varchar(60)  not null,
    display_name     varchar(120) not null,
    avatar_key       varchar(80),
    is_private       boolean      not null default false,
    status           varchar(10)  not null check (status in ('ACTIVE', 'DELETED')),
    profile_version  bigint       not null,                 -- aggregateVersion aplicado (ignora eventos antigos/duplicados)
    updated_at       timestamp with time zone not null
);

-- Quem segue quem (follow.*): só o necessário para o feed e a privacidade. O dono desta informação é o grafo.
create table follow_edge (
    follower_id  uuid not null,
    followee_id  uuid not null,
    relation_id  uuid not null,                             -- vínculo que gerou a aresta (protege contra evento antigo)
    created_at   timestamp with time zone not null,
    primary key (follower_id, followee_id)
);
create index idx_follow_edge_followee on follow_edge (followee_id);

-- "Túmulos" de vínculos removidos: um follow.created atrasado de um vínculo já removido não recria a aresta (podados após 7 dias).
create table follow_removed (
    relation_id  uuid primary key,
    removed_at   timestamp with time zone not null
);

create table post (
    id             uuid primary key,                        -- UUIDv7 (ordenável no tempo) [D3]
    author_id      uuid not null references author_snapshot (user_id),
    parent_id      uuid references post (id) on delete cascade,   -- resposta; apagar o pai apaga as respostas [D7]
    body           varchar(1000),
    image_key      varchar(80) unique,                      -- po_<uuid>.<ext>, objeto no bucket (chave gerada no servidor)
    image_kind     varchar(5) check (image_kind in ('IMAGE', 'GIF')),
    image_width    smallint,
    image_height   smallint,
    likes_count    integer not null default 0 check (likes_count >= 0),
    replies_count  integer not null default 0 check (replies_count >= 0),
    created_at     timestamp with time zone not null,
    constraint post_has_content check (body is not null or image_key is not null),
    constraint post_image_consistent check ((image_key is null) = (image_kind is null)),
    constraint post_body_not_blank check (body is null or length(btrim(body)) > 0)
);
-- Listas paginadas por cursor (created_at desc, id desc), sem OFFSET.
create index idx_post_author on post (author_id, created_at desc, id desc) where parent_id is null;          -- aba "Posts"
create index idx_post_author_all on post (author_id, created_at desc, id desc);                             -- "com interações"
create index idx_post_author_media on post (author_id, created_at desc, id desc) where image_key is not null; -- "Mídia"
create index idx_post_replies on post (parent_id, created_at desc, id desc) where parent_id is not null;
create index idx_post_feed on post (created_at desc, id desc) where parent_id is null;

create table post_like (
    post_id     uuid not null references post (id) on delete cascade,
    user_id     uuid not null,
    created_at  timestamp with time zone not null,
    primary key (post_id, user_id)                          -- "curti?" e unicidade
);
create index idx_post_like_user on post_like (user_id, created_at desc);

-- Transactional outbox: o evento é gravado na mesma transação da mudança e publicado depois no RabbitMQ.
create table outbox_event (
    seq                bigint generated always as identity primary key,   -- ordem de publicação (não depende de relógio)
    id                 uuid        not null unique,
    type               varchar(80) not null,
    aggregate_id       uuid        not null,
    aggregate_version  bigint      not null,
    payload            text        not null,
    created_at         timestamp with time zone not null,
    published_at       timestamp with time zone
);
create index idx_outbox_unpublished on outbox_event (seq) where published_at is null;

-- Inbox: garante processamento idempotente das mensagens recebidas (entrega é "pelo menos uma vez").
create table processed_message (
    id            uuid primary key,
    processed_at  timestamp with time zone not null
);
