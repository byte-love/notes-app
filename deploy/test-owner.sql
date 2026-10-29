-- 只 grant 权限不够：ALTER TABLE 要的是属主。每次从正式库恢复后都要跑一遍。
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP
    EXECUTE format('alter table public.%I owner to notes_dev', r.tablename);
  END LOOP;
  FOR r IN SELECT sequencename FROM pg_sequences WHERE schemaname = 'public' LOOP
    EXECUTE format('alter sequence public.%I owner to notes_dev', r.sequencename);
  END LOOP;
END $$;
