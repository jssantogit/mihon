begin;

select plan(13);

insert into auth.users (
    id,
    aud,
    role,
    email,
    encrypted_password,
    email_confirmed_at,
    created_at,
    updated_at
)
values (
    '00000000-0000-0000-0000-00000000000c'::uuid,
    'authenticated',
    'authenticated',
    'sync-user@example.com',
    '',
    now(),
    now(),
    now()
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000c', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-00000000000c","role":"authenticated"}',
    true
);

select is(
    public.sync_apply_mutation_batch(
        '{
          "mutationId":"10000000-0000-0000-0000-000000000001",
          "originClientId":"device-a",
          "domain":"LIBRARY",
          "baseCursor":0,
          "operations":[
            {"type":"set","recordId":"replay-title","fieldPath":"status","value":"READING"}
          ]
        }'::jsonb
    ),
    public.sync_apply_mutation_batch(
        '{
          "mutationId":"10000000-0000-0000-0000-000000000001",
          "originClientId":"device-a",
          "domain":"LIBRARY",
          "baseCursor":0,
          "operations":[
            {"type":"set","recordId":"replay-title","fieldPath":"status","value":"READING"}
          ]
        }'::jsonb
    ),
    'replaying one mutation returns the same result cursor'
);

select is(
    (
        select count(*)::integer
        from public.tsuzuki_sync_events
        where record_id = 'replay-title'
    ),
    1,
    'replayed mutation applies one effect'
);

select throws_ok(
    $$
    select public.sync_apply_mutation_batch(
        '{
          "mutationId":"10000000-0000-0000-0000-000000000001",
          "originClientId":"device-a",
          "domain":"LIBRARY",
          "baseCursor":0,
          "operations":[
            {"type":"set","recordId":"replay-title","fieldPath":"status","value":"COMPLETED"}
          ]
        }'::jsonb
    )
    $$,
    '22023',
    'mutation ID reused with a different request',
    'same mutation ID with another payload is rejected'
);

select ok(
    (
        select result_cursor is not null
        from public.tsuzuki_sync_mutations
        where mutation_id = '10000000-0000-0000-0000-000000000001'::uuid
    ),
    'committed idempotency row has a result cursor'
);

select lives_ok(
    $$
    select public.sync_apply_mutation_batch(
        '{
          "mutationId":"10000000-0000-0000-0000-000000000002",
          "originClientId":"device-a",
          "domain":"LIBRARY",
          "baseCursor":0,
          "operations":[
            {"type":"set","recordId":"merge-title","fieldPath":"status","value":"READING"}
          ]
        }'::jsonb
    )
    $$,
    'first independent field applies'
);

select lives_ok(
    $$
    select public.sync_apply_mutation_batch(
        '{
          "mutationId":"10000000-0000-0000-0000-000000000003",
          "originClientId":"device-b",
          "domain":"LIBRARY",
          "baseCursor":0,
          "operations":[
            {"type":"set","recordId":"merge-title","fieldPath":"score","value":8}
          ]
        }'::jsonb
    )
    $$,
    'second independent field merges despite stale global cursor'
);

select is(
    (
        select count(*)::integer
        from public.tsuzuki_sync_conflicts
        where record_id = 'merge-title'
    ),
    0,
    'independent fields do not create a conflict'
);

select public.sync_apply_mutation_batch(
    '{
      "mutationId":"10000000-0000-0000-0000-000000000004",
      "originClientId":"device-a",
      "domain":"PROGRESS",
      "baseCursor":0,
      "operations":[
        {"type":"set","recordId":"progress-title","fieldPath":"chapter","value":10}
      ]
    }'::jsonb
);

select public.sync_apply_mutation_batch(
    '{
      "mutationId":"10000000-0000-0000-0000-000000000005",
      "originClientId":"device-b",
      "domain":"PROGRESS",
      "baseCursor":0,
      "operations":[
        {"type":"set","recordId":"progress-title","fieldPath":"chapter","value":20}
      ]
    }'::jsonb
);

select is(
    (
        select count(*)::integer
        from public.tsuzuki_sync_conflicts
        where record_id = 'progress-title'
          and field_path = 'chapter'
          and conflict_type = 'FIELD_DIVERGENCE'
    ),
    1,
    'same-field divergent edits produce one explicit conflict'
);

select is(
    (
        select value
        from public.tsuzuki_sync_fields
        where domain = 'PROGRESS'
          and record_id = 'progress-title'
          and field_path = 'chapter'
    ),
    '10'::jsonb,
    'same-field conflict does not overwrite accepted remote value'
);

select public.sync_apply_mutation_batch(
    '{
      "mutationId":"10000000-0000-0000-0000-000000000006",
      "originClientId":"device-a",
      "domain":"LIBRARY",
      "baseCursor":0,
      "operations":[
        {"type":"set","recordId":"delete-edit-title","fieldPath":"status","value":"READING"}
      ]
    }'::jsonb
);

select public.sync_apply_mutation_batch(
    jsonb_build_object(
        'mutationId', '10000000-0000-0000-0000-000000000007',
        'originClientId', 'device-b',
        'domain', 'LIBRARY',
        'baseCursor', public.sync_get_cursor('LIBRARY'),
        'operations', jsonb_build_array(
            jsonb_build_object(
                'type', 'set',
                'recordId', 'delete-edit-title',
                'fieldPath', 'score',
                'value', 9
            )
        )
    )
);

select public.sync_apply_mutation_batch(
    '{
      "mutationId":"10000000-0000-0000-0000-000000000008",
      "originClientId":"device-a",
      "domain":"LIBRARY",
      "baseCursor":0,
      "operations":[
        {"type":"delete","recordId":"delete-edit-title"}
      ]
    }'::jsonb
);

select is(
    (
        select count(*)::integer
        from public.tsuzuki_sync_conflicts
        where record_id = 'delete-edit-title'
          and conflict_type = 'DELETE_EDIT'
    ),
    1,
    'stale delete against a later edit produces DELETE_EDIT'
);

select is(
    public.sync_claim_external_identity('kitsu', '1', 'canon-a'),
    'canon-a',
    'first verified external identity claim wins'
);

select is(
    public.sync_claim_external_identity('kitsu', '1', 'canon-b'),
    'canon-a',
    'same verified external identity converges to the existing canonical ID'
);

select isnt(
    public.sync_claim_external_identity('kitsu', 'same-title-a', 'canon-title-a'),
    public.sync_claim_external_identity('kitsu', 'same-title-b', 'canon-title-b'),
    'different external identities remain distinct even when display titles could match'
);

select is(
    (public.sync_snapshot_domain('LIBRARY') ->> 'cursor')::bigint,
    public.sync_get_cursor('LIBRARY'),
    'snapshot captures the accepted domain cursor'
);

select * from finish();
rollback;
