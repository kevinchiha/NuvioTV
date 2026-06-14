-- get_sync_owner(): returns auth.uid()::text for a logged-in member; NULL for anon.
do $$
declare a uuid := '11111111-1111-1111-1111-111111111111';
        v text;
begin
  perform public.test_login(a);
  select public.get_sync_owner() into v;
  assert v = a::text, format('expected owner %s, got %s', a, v);

  perform public.test_logout();
  select public.get_sync_owner() into v;
  assert v is null, format('anon owner should be NULL, got %s', v);

  raise notice 'get_sync_owner OK';
end $$;
