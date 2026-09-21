begin;

select plan(8);

insert into auth.users (
    id,
    aud,
    role,
    email,
    encrypted_password,
    created_at,
    updated_at
)
values
    (
        '00000000-0000-0000-0000-00000000000a'::uuid,
        'authenticated',
        'authenticated',
        'user-a@example.com',
        '',
        now(),
        now()
    ),
    (
        '00000000-0000-0000-0000-00000000000b'::uuid,
        'authenticated',
        'authenticated',
        'user-b@example.com',
        '',
        now(),
        now()
    );

insert into tsuzuki_private.tsuzuki_sync_fields (
    user_id,
    domain,
    record_id,
    field_path,
    value,
    is_removed,
    last_event_id
)
values
    (
        '00000000-0000-0000-0000-00000000000a'::uuid,
        'LIBRARY',
        'a-title',
        'status',
        '"READING"'::jsonb,
        false,
        0
    ),
    (
        '00000000-0000-0000-0000-00000000000b'::uuid,
        'LIBRARY',
        'b-title',
        'status',
        '"COMPLETED"'::jsonb,
        false,
        0
    );

insert into tsuzuki_private.tsuzuki_sync_conflicts (
    user_id,
    domain,
    record_id,
    field_path,
    conflict_type,
    local_value,
    remote_value,
    local_mutation_id
)
values (
    '00000000-0000-0000-0000-00000000000b'::uuid,
    'LIBRARY',
    'b-title',
    'status',
    'FIELD_DIVERGENCE',
    '"READING"'::jsonb,
    '"COMPLETED"'::jsonb,
    '00000000-0000-0000-0000-00000000b001'::uuid
);

select set_config(
    'test.foreign_conflict_id',
    (
        select conflict_id::text
        from tsuzuki_private.tsuzuki_sync_conflicts
        where user_id = '00000000-0000-0000-0000-00000000000b'::uuid
        limit 1
    ),
    true
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated"}',
    true
);

select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_sync_fields),
    1,
    'RLS exposes only the authenticated user rows'
);

select is(
    (select record_id from tsuzuki_private.tsuzuki_sync_fields limit 1),
    'a-title',
    'RLS hides another user sync field'
);

select throws_ok(
    $
    update tsuzuki_private.tsuzuki_sync_fields
    set value = '"HACKED"'::jsonb
    where user_id = '00000000-0000-0000-0000-00000000000b'::uuid
    $,
    '42501',
    null,
    'authenticated clients cannot mutate sync tables directly'
);

select is(
    public.sync_ack_conflict(
        current_setting('test.foreign_conflict_id')::bigint
    ),
    false,
    'conflict acknowledgement cannot cross user scope'
);

select is(
    public.sync_get_cursor('LIBRARY'),
    0::bigint,
    'cursor RPC is scoped to the authenticated user'
);

select is(
    jsonb_array_length(public.sync_pull_delta('LIBRARY', 0, 100)),
    0,
    'delta RPC does not leak another user events'
);

reset role;

select is(
    (
        select value
        from tsuzuki_private.tsuzuki_sync_fields
        where user_id = '00000000-0000-0000-0000-00000000000b'::uuid
          and record_id = 'b-title'
          and field_path = 'status'
    ),
    '"COMPLETED"'::jsonb,
    'RLS prevented cross-user update'
);

select is(
    (
        select resolved_at is null
        from tsuzuki_private.tsuzuki_sync_conflicts
        where user_id = '00000000-0000-0000-0000-00000000000b'::uuid
        limit 1
    ),
    true,
    'foreign conflict remains unresolved'
);

set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);

select throws_ok(
    $$select public.sync_get_cursor('LIBRARY')$$,
    '42501',
    null,
    'anonymous callers cannot execute sync RPCs'
);

reset role;

select * from finish();
rollback;
