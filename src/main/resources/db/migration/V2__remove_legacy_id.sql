-- O id inteiro temporário (ponte com o backend legado) não tem mais uso: o legado foi desligado e todos os serviços
-- identificam o usuário só pelo UUID da conta. O parâmetro `autor` passa a aceitar só UUID.
alter table author_snapshot drop column legacy_id;
