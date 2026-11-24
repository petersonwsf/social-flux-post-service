-- Várias imagens por postagem (até 5, em ordem). `post.image_*` continua guardando a PRIMEIRA (capa), para o filtro de mídia e
-- a regra "texto ou anexo"; `post_image` guarda todas, inclusive a capa.
create table post_image (
    post_id       uuid         not null references post (id) on delete cascade,
    position      smallint     not null check (position between 0 and 4),
    image_key     varchar(80)  not null unique,                -- po_<uuid>.<ext>, objeto no bucket (chave gerada no servidor)
    image_kind    varchar(5)   not null check (image_kind in ('IMAGE', 'GIF')),
    image_width   smallint,
    image_height  smallint,
    primary key (post_id, position)
);

-- As postagens que já tinham imagem ganham a linha da capa.
insert into post_image (post_id, position, image_key, image_kind, image_width, image_height)
select id, 0, image_key, image_kind, image_width, image_height from post where image_key is not null;
