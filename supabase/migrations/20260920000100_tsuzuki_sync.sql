create schema if not exists extensions;
create schema if not exists tsuzuki_private;
revoke all on schema tsuzuki_private from public, anon;
grant usage on schema tsuzuki_private to authenticated;
create extension if not exists pgcrypto with schema extensions;

create table tsuzuki_private.tsuzuki_sync_fields (
    user_id uuid not null references auth.users(id) on delete cascade,
    domain text not null check (btrim(domain) <> ''),
    record_id text not null check (btrim(record_id) <> ''),
    field_path text not null check (btrim(field_path) <> ''),
    value jsonb,
    is_removed boolean not null default false,
    last_event_id bigint not null default 0 check (last_event_id >= 0),
    primary key (user_id, domain, record_id, field_path)
);

create table tsuzuki_private.tsuzuki_sync_records (
    user_id uuid not null references auth.users(id) on delete cascade,
    domain text not null check (btrim(domain) <> ''),
    record_id text not null check (btrim(record_id) <> ''),
    is_deleted boolean not null default false,
    delete_last_event_id bigint not null default 0 check (delete_last_event_id >= 0),
    primary key (user_id, domain, record_id)
);

create table tsuzuki_private.tsuzuki_sync_events (
    event_id bigint generated always as identity primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    domain text not null check (btrim(domain) <> ''),
    record_id text not null check (btrim(record_id) <> ''),
    field_path text,
    operation text not null check (operation in ('set', 'remove', 'delete')),
    value jsonb,
    origin_client_id text not null check (btrim(origin_client_id) <> ''),
    mutation_id uuid not null,
    created_at timestamptz not null default now(),
    check (
        (operation = 'delete' and field_path is null)
        or
        (operation in ('set', 'remove') and field_path is not null and btrim(field_path) <> '')
    )
);

create index tsuzuki_sync_events_user_domain_cursor
on tsuzuki_private.tsuzuki_sync_events(user_id, domain, event_id);

create index tsuzuki_sync_events_user_mutation
on tsuzuki_private.tsuzuki_sync_events(user_id, mutation_id);

create table tsuzuki_private.tsuzuki_sync_mutations (
    user_id uuid not null references auth.users(id) on delete cascade,
    mutation_id uuid not null,
    request_hash text not null check (btrim(request_hash) <> ''),
    result_cursor bigint check (result_cursor is null or result_cursor >= 0),
    created_at timestamptz not null default now(),
    primary key (user_id, mutation_id)
);

create table tsuzuki_private.tsuzuki_canonical_identity_claims (
    user_id uuid not null references auth.users(id) on delete cascade,
    provider text not null check (btrim(provider) <> ''),
    external_id text not null check (btrim(external_id) <> ''),
    canonical_title_id text not null check (btrim(canonical_title_id) <> ''),
    claimed_at timestamptz not null default now(),
    primary key (user_id, provider, external_id)
);

create table tsuzuki_private.tsuzuki_sync_conflicts (
    conflict_id bigint generated always as identity primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    domain text not null check (btrim(domain) <> ''),
    record_id text not null check (btrim(record_id) <> ''),
    field_path text,
    conflict_type text not null check (conflict_type in ('FIELD_DIVERGENCE', 'DELETE_EDIT')),
    local_value jsonb,
    remote_value jsonb,
    local_mutation_id uuid not null,
    created_at timestamptz not null default now(),
    resolved_at timestamptz
);

create index tsuzuki_sync_conflicts_user_unresolved
on tsuzuki_private.tsuzuki_sync_conflicts(user_id, conflict_id)
where resolved_at is null;

alter table tsuzuki_private.tsuzuki_sync_fields enable row level security;
alter table tsuzuki_private.tsuzuki_sync_records enable row level security;
alter table tsuzuki_private.tsuzuki_sync_events enable row level security;
alter table tsuzuki_private.tsuzuki_sync_mutations enable row level security;
alter table tsuzuki_private.tsuzuki_canonical_identity_claims enable row level security;
alter table tsuzuki_private.tsuzuki_sync_conflicts enable row level security;

create policy "users own sync fields"
on tsuzuki_private.tsuzuki_sync_fields
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy "users own sync records"
on tsuzuki_private.tsuzuki_sync_records
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy "users own sync events"
on tsuzuki_private.tsuzuki_sync_events
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy "users own sync mutations"
on tsuzuki_private.tsuzuki_sync_mutations
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy "users own canonical identity claims"
on tsuzuki_private.tsuzuki_canonical_identity_claims
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy "users own sync conflicts"
on tsuzuki_private.tsuzuki_sync_conflicts
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

revoke all on table tsuzuki_private.tsuzuki_sync_fields from anon;
revoke all on table tsuzuki_private.tsuzuki_sync_records from anon;
revoke all on table tsuzuki_private.tsuzuki_sync_events from anon;
revoke all on table tsuzuki_private.tsuzuki_sync_mutations from anon;
revoke all on table tsuzuki_private.tsuzuki_canonical_identity_claims from anon;
revoke all on table tsuzuki_private.tsuzuki_sync_conflicts from anon;

revoke insert, update, delete on table tsuzuki_private.tsuzuki_sync_fields from authenticated;
revoke insert, update, delete on table tsuzuki_private.tsuzuki_sync_records from authenticated;
revoke insert, update, delete on table tsuzuki_private.tsuzuki_sync_events from authenticated;
revoke insert, update, delete on table tsuzuki_private.tsuzuki_sync_mutations from authenticated;
revoke insert, update, delete on table tsuzuki_private.tsuzuki_canonical_identity_claims from authenticated;
revoke insert, update, delete on table tsuzuki_private.tsuzuki_sync_conflicts from authenticated;

grant select on table tsuzuki_private.tsuzuki_sync_fields to authenticated;
grant select on table tsuzuki_private.tsuzuki_sync_records to authenticated;
grant select on table tsuzuki_private.tsuzuki_sync_events to authenticated;
grant select on table tsuzuki_private.tsuzuki_sync_mutations to authenticated;
grant select on table tsuzuki_private.tsuzuki_canonical_identity_claims to authenticated;
grant select on table tsuzuki_private.tsuzuki_sync_conflicts to authenticated;

revoke all on sequence tsuzuki_private.tsuzuki_sync_events_event_id_seq from authenticated;
revoke all on sequence tsuzuki_private.tsuzuki_sync_conflicts_conflict_id_seq from authenticated;

create or replace function public.sync_claim_external_identity(
    p_provider text,
    p_external_id text,
    p_proposed_canonical_title_id text
)
returns text
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_user_id uuid := auth.uid();
    v_canonical_title_id text;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    if nullif(btrim(p_provider), '') is null
        or nullif(btrim(p_external_id), '') is null
        or nullif(btrim(p_proposed_canonical_title_id), '') is null
    then
        raise exception using
            errcode = '22023',
            message = 'provider, external ID, and canonical title ID are required';
    end if;

    insert into tsuzuki_private.tsuzuki_canonical_identity_claims (
        user_id,
        provider,
        external_id,
        canonical_title_id
    )
    values (
        v_user_id,
        p_provider,
        p_external_id,
        p_proposed_canonical_title_id
    )
    on conflict (user_id, provider, external_id) do nothing;

    select c.canonical_title_id
    into v_canonical_title_id
    from tsuzuki_private.tsuzuki_canonical_identity_claims as c
    where c.user_id = v_user_id
      and c.provider = p_provider
      and c.external_id = p_external_id;

    if v_canonical_title_id is null then
        raise exception using
            errcode = 'XX000',
            message = 'failed to resolve canonical identity claim';
    end if;

    return v_canonical_title_id;
end;
$$;

create or replace function public.sync_apply_mutation_batch(
    p_request jsonb
)
returns bigint
language plpgsql
security definer
set search_path = pg_catalog, public, extensions
as $$
declare
    v_user_id uuid := auth.uid();
    v_mutation_id uuid;
    v_origin_client_id text;
    v_domain text;
    v_base_cursor bigint;
    v_operations jsonb;
    v_request_hash text;
    v_inserted integer;
    v_existing_hash text;
    v_existing_cursor bigint;
    v_result_cursor bigint;
    v_operation jsonb;
    v_operation_type text;
    v_record_id text;
    v_field_path text;
    v_requested_value jsonb;
    v_requested_state jsonb;
    v_remote_state jsonb;
    v_record_deleted boolean;
    v_delete_event_id bigint;
    v_field_value jsonb;
    v_field_removed boolean;
    v_field_event_id bigint;
    v_latest_field_event_id bigint;
    v_event_id bigint;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    if jsonb_typeof(p_request) <> 'object' then
        raise exception using
            errcode = '22023',
            message = 'mutation request must be a JSON object';
    end if;

    begin
        v_mutation_id := (p_request ->> 'mutationId')::uuid;
        v_base_cursor := (p_request ->> 'baseCursor')::bigint;
    exception
        when invalid_text_representation then
            raise exception using
                errcode = '22023',
                message = 'mutationId must be a UUID and baseCursor must be an integer';
    end;

    v_origin_client_id := nullif(btrim(p_request ->> 'originClientId'), '');
    v_domain := nullif(btrim(p_request ->> 'domain'), '');
    v_operations := p_request -> 'operations';

    if v_mutation_id is null
        or v_origin_client_id is null
        or v_domain is null
        or v_base_cursor is null
        or v_base_cursor < 0
        or v_operations is null
        or jsonb_typeof(v_operations) <> 'array'
        or jsonb_array_length(v_operations) = 0
    then
        raise exception using
            errcode = '22023',
            message = 'mutation request is missing required fields';
    end if;

    for v_operation in
        select item.value
        from jsonb_array_elements(v_operations) as item(value)
    loop
        if jsonb_typeof(v_operation) <> 'object' then
            raise exception using
                errcode = '22023',
                message = 'each mutation operation must be a JSON object';
        end if;

        v_operation_type := v_operation ->> 'type';
        v_record_id := nullif(btrim(v_operation ->> 'recordId'), '');
        v_field_path := nullif(btrim(v_operation ->> 'fieldPath'), '');

        if v_record_id is null
            or v_operation_type is null
            or v_operation_type not in ('set', 'remove', 'delete')
        then
            raise exception using
                errcode = '22023',
                message = 'invalid mutation operation';
        end if;

        if v_operation_type = 'set'
            and (v_field_path is null or not (v_operation ? 'value'))
        then
            raise exception using
                errcode = '22023',
                message = 'set operation requires fieldPath and value';
        end if;

        if v_operation_type = 'remove' and v_field_path is null then
            raise exception using
                errcode = '22023',
                message = 'remove operation requires fieldPath';
        end if;

        if v_operation_type = 'delete' and v_field_path is not null then
            raise exception using
                errcode = '22023',
                message = 'delete operation must not contain fieldPath';
        end if;
    end loop;

    if exists (
        select 1
        from (
            select
                item.value ->> 'recordId' as record_id,
                case
                    when item.value ->> 'type' = 'delete' then '__DELETE__'
                    else item.value ->> 'fieldPath'
                end as field_path,
                count(*) as operation_count
            from jsonb_array_elements(v_operations) as item(value)
            group by 1, 2
            having count(*) > 1
        ) as duplicates
    ) then
        raise exception using
            errcode = '22023',
            message = 'duplicate operation for record and field';
    end if;

    if exists (
        select 1
        from jsonb_array_elements(v_operations) as item(value)
        group by item.value ->> 'recordId'
        having
            bool_or(item.value ->> 'type' = 'delete')
            and count(*) > 1
    ) then
        raise exception using
            errcode = '22023',
            message = 'delete cannot be combined with field edits for one record';
    end if;

    v_request_hash := encode(
        digest(convert_to(p_request::text, 'UTF8'), 'sha256'),
        'hex'
    );

    insert into tsuzuki_private.tsuzuki_sync_mutations (
        user_id,
        mutation_id,
        request_hash,
        result_cursor
    )
    values (
        v_user_id,
        v_mutation_id,
        v_request_hash,
        null
    )
    on conflict (user_id, mutation_id) do nothing;

    get diagnostics v_inserted = row_count;

    if v_inserted = 0 then
        select
            m.request_hash,
            m.result_cursor
        into
            v_existing_hash,
            v_existing_cursor
        from tsuzuki_private.tsuzuki_sync_mutations as m
        where m.user_id = v_user_id
          and m.mutation_id = v_mutation_id
        for update;

        if v_existing_hash is distinct from v_request_hash then
            raise exception using
                errcode = '22023',
                message = 'mutation ID reused with a different request';
        end if;

        if v_existing_cursor is null then
            raise exception using
                errcode = 'XX001',
                message = 'committed mutation is missing its result cursor';
        end if;

        return v_existing_cursor;
    end if;

    for v_record_id in
        select distinct item.value ->> 'recordId'
        from jsonb_array_elements(v_operations) as item(value)
        order by 1
    loop
        perform pg_advisory_xact_lock(
            hashtextextended(
                v_user_id::text || chr(31) || v_domain || chr(31) || v_record_id,
                0
            )
        );
    end loop;

    for v_operation in
        select item.value
        from jsonb_array_elements(v_operations) with ordinality as item(value, ordinal)
        order by item.ordinal
    loop
        v_operation_type := v_operation ->> 'type';
        v_record_id := v_operation ->> 'recordId';
        v_field_path := nullif(btrim(v_operation ->> 'fieldPath'), '');
        v_requested_value := v_operation -> 'value';

        v_record_deleted := false;
        v_delete_event_id := 0;

        select
            r.is_deleted,
            r.delete_last_event_id
        into
            v_record_deleted,
            v_delete_event_id
        from tsuzuki_private.tsuzuki_sync_records as r
        where r.user_id = v_user_id
          and r.domain = v_domain
          and r.record_id = v_record_id;

        if not found then
            v_record_deleted := false;
            v_delete_event_id := 0;
        end if;

        if v_operation_type in ('set', 'remove') then
            v_requested_state := case
                when v_operation_type = 'remove'
                    then jsonb_build_object('removed', true)
                else jsonb_build_object(
                    'removed',
                    false,
                    'value',
                    v_requested_value
                )
            end;

            if v_delete_event_id > v_base_cursor then
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
                    v_user_id,
                    v_domain,
                    v_record_id,
                    v_field_path,
                    'DELETE_EDIT',
                    v_requested_state,
                    jsonb_build_object(
                        'deleted',
                        v_record_deleted,
                        'deleteEventId',
                        v_delete_event_id
                    ),
                    v_mutation_id
                );
                continue;
            end if;

            -- A resurrection starts from the tombstone, not from fields that
            -- existed before the accepted delete event.
            if v_record_deleted and v_delete_event_id <= v_base_cursor then
                delete from tsuzuki_private.tsuzuki_sync_fields as stale
                where stale.user_id = v_user_id
                  and stale.domain = v_domain
                  and stale.record_id = v_record_id;
            end if;

            v_field_value := null;
            v_field_removed := false;
            v_field_event_id := 0;

            select
                f.value,
                f.is_removed,
                f.last_event_id
            into
                v_field_value,
                v_field_removed,
                v_field_event_id
            from tsuzuki_private.tsuzuki_sync_fields as f
            where f.user_id = v_user_id
              and f.domain = v_domain
              and f.record_id = v_record_id
              and f.field_path = v_field_path;

            if not found then
                v_field_value := null;
                v_field_removed := false;
                v_field_event_id := 0;
            end if;

            if v_field_event_id > v_base_cursor then
                if (
                    v_operation_type = 'set'
                    and not v_field_removed
                    and v_field_value is not distinct from v_requested_value
                ) or (
                    v_operation_type = 'remove'
                    and v_field_removed
                ) then
                    continue;
                end if;

                v_remote_state := case
                    when v_field_removed
                        then jsonb_build_object('removed', true)
                    else jsonb_build_object(
                        'removed',
                        false,
                        'value',
                        v_field_value
                    )
                end;

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
                    v_user_id,
                    v_domain,
                    v_record_id,
                    v_field_path,
                    'FIELD_DIVERGENCE',
                    v_requested_state,
                    v_remote_state,
                    v_mutation_id
                );
                continue;
            end if;

            insert into tsuzuki_private.tsuzuki_sync_events (
                user_id,
                domain,
                record_id,
                field_path,
                operation,
                value,
                origin_client_id,
                mutation_id
            )
            values (
                v_user_id,
                v_domain,
                v_record_id,
                v_field_path,
                v_operation_type,
                case when v_operation_type = 'set' then v_requested_value else null end,
                v_origin_client_id,
                v_mutation_id
            )
            returning event_id into v_event_id;

            insert into tsuzuki_private.tsuzuki_sync_records (
                user_id,
                domain,
                record_id,
                is_deleted,
                delete_last_event_id
            )
            values (
                v_user_id,
                v_domain,
                v_record_id,
                false,
                v_delete_event_id
            )
            on conflict (user_id, domain, record_id) do update
            set is_deleted = false;

            insert into tsuzuki_private.tsuzuki_sync_fields (
                user_id,
                domain,
                record_id,
                field_path,
                value,
                is_removed,
                last_event_id
            )
            values (
                v_user_id,
                v_domain,
                v_record_id,
                v_field_path,
                case when v_operation_type = 'set' then v_requested_value else null end,
                v_operation_type = 'remove',
                v_event_id
            )
            on conflict (user_id, domain, record_id, field_path) do update
            set
                value = excluded.value,
                is_removed = excluded.is_removed,
                last_event_id = excluded.last_event_id;

            continue;
        end if;

        select coalesce(max(f.last_event_id), 0)
        into v_latest_field_event_id
        from tsuzuki_private.tsuzuki_sync_fields as f
        where f.user_id = v_user_id
          and f.domain = v_domain
          and f.record_id = v_record_id;

        if v_latest_field_event_id > v_base_cursor
            or (v_delete_event_id > v_base_cursor and not v_record_deleted)
        then
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
                v_user_id,
                v_domain,
                v_record_id,
                null,
                'DELETE_EDIT',
                jsonb_build_object('deleted', true),
                jsonb_build_object(
                    'deleted',
                    v_record_deleted,
                    'deleteEventId',
                    v_delete_event_id,
                    'latestFieldEventId',
                    v_latest_field_event_id
                ),
                v_mutation_id
            );
            continue;
        end if;

        if v_delete_event_id > v_base_cursor and v_record_deleted then
            continue;
        end if;

        insert into tsuzuki_private.tsuzuki_sync_events (
            user_id,
            domain,
            record_id,
            field_path,
            operation,
            value,
            origin_client_id,
            mutation_id
        )
        values (
            v_user_id,
            v_domain,
            v_record_id,
            null,
            'delete',
            null,
            v_origin_client_id,
            v_mutation_id
        )
        returning event_id into v_event_id;

        insert into tsuzuki_private.tsuzuki_sync_records (
            user_id,
            domain,
            record_id,
            is_deleted,
            delete_last_event_id
        )
        values (
            v_user_id,
            v_domain,
            v_record_id,
            true,
            v_event_id
        )
        on conflict (user_id, domain, record_id) do update
        set
            is_deleted = true,
            delete_last_event_id = excluded.delete_last_event_id;
    end loop;

    select coalesce(max(e.event_id), 0)
    into v_result_cursor
    from tsuzuki_private.tsuzuki_sync_events as e
    where e.user_id = v_user_id
      and e.domain = v_domain;

    update tsuzuki_private.tsuzuki_sync_mutations as m
    set result_cursor = v_result_cursor
    where m.user_id = v_user_id
      and m.mutation_id = v_mutation_id;

    return v_result_cursor;
end;
$$;

create or replace function public.sync_pull_delta(
    p_domain text,
    p_since_event_id bigint,
    p_limit integer default 500
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
    v_user_id uuid := auth.uid();
    v_result jsonb;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    if nullif(btrim(p_domain), '') is null
        or p_since_event_id is null
        or p_since_event_id < 0
        or p_limit is null
        or p_limit < 1
        or p_limit > 1000
    then
        raise exception using
            errcode = '22023',
            message = 'invalid delta request';
    end if;

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'eventId', delta.event_id,
                'domain', delta.domain,
                'recordId', delta.record_id,
                'fieldPath', delta.field_path,
                'operation', delta.operation,
                'value', delta.value,
                'originClientId', delta.origin_client_id,
                'mutationId', delta.mutation_id,
                'createdAt', delta.created_at
            )
            order by delta.event_id
        ),
        '[]'::jsonb
    )
    into v_result
    from (
        select
            e.event_id,
            e.domain,
            e.record_id,
            e.field_path,
            e.operation,
            e.value,
            e.origin_client_id,
            e.mutation_id,
            e.created_at
        from tsuzuki_private.tsuzuki_sync_events as e
        where e.user_id = v_user_id
          and e.domain = p_domain
          and e.event_id > p_since_event_id
        order by e.event_id
        limit p_limit
    ) as delta;

    return v_result;
end;
$$;

create or replace function public.sync_get_cursor(
    p_domain text
)
returns bigint
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
    v_user_id uuid := auth.uid();
    v_cursor bigint;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    if nullif(btrim(p_domain), '') is null then
        raise exception using
            errcode = '22023',
            message = 'domain is required';
    end if;

    select coalesce(max(e.event_id), 0)
    into v_cursor
    from tsuzuki_private.tsuzuki_sync_events as e
    where e.user_id = v_user_id
      and e.domain = p_domain;

    return v_cursor;
end;
$$;

create or replace function public.sync_snapshot_domain(
    p_domain text
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
    v_user_id uuid := auth.uid();
    v_snapshot jsonb;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    if nullif(btrim(p_domain), '') is null then
        raise exception using
            errcode = '22023',
            message = 'domain is required';
    end if;

    select jsonb_build_object(
        'cursor',
        snapshot_cursor.event_cursor,
        'records',
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'recordId',
                        r.record_id,
                        'isDeleted',
                        r.is_deleted,
                        'fields',
                        coalesce(
                            (
                                select jsonb_object_agg(
                                    f.field_path,
                                    f.value
                                    order by f.field_path
                                )
                                from tsuzuki_private.tsuzuki_sync_fields as f
                                where f.user_id = v_user_id
                                  and f.domain = p_domain
                                  and f.record_id = r.record_id
                                  and not f.is_removed
                                  and f.last_event_id <= snapshot_cursor.event_cursor
                            ),
                            '{}'::jsonb
                        )
                    )
                    order by r.record_id
                )
                from tsuzuki_private.tsuzuki_sync_records as r
                where r.user_id = v_user_id
                  and r.domain = p_domain
                  and r.delete_last_event_id <= snapshot_cursor.event_cursor
            ),
            '[]'::jsonb
        )
    )
    into v_snapshot
    from (
        select coalesce(max(e.event_id), 0) as event_cursor
        from tsuzuki_private.tsuzuki_sync_events as e
        where e.user_id = v_user_id
          and e.domain = p_domain
    ) as snapshot_cursor;

    return v_snapshot;
end;
$$;

create or replace function public.sync_get_mutation_conflicts(
    p_mutation_id uuid
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public, tsuzuki_private
as $
declare
    v_user_id uuid := auth.uid();
    v_result jsonb;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    if p_mutation_id is null then
        raise exception using
            errcode = '22023',
            message = 'mutation ID is required';
    end if;

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'conflictId', c.conflict_id,
                'recordId', c.record_id,
                'fieldPath', c.field_path,
                'kind', c.conflict_type,
                'localValue', c.local_value,
                'remoteValue', c.remote_value
            )
            order by c.conflict_id
        ),
        '[]'::jsonb
    )
    into v_result
    from tsuzuki_private.tsuzuki_sync_conflicts as c
    where c.user_id = v_user_id
      and c.local_mutation_id = p_mutation_id
      and c.resolved_at is null;

    return v_result;
end;
$;

create or replace function public.sync_ack_conflict(
    p_conflict_id bigint
)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_user_id uuid := auth.uid();
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    if p_conflict_id is null or p_conflict_id <= 0 then
        raise exception using
            errcode = '22023',
            message = 'conflict ID must be positive';
    end if;

    update tsuzuki_private.tsuzuki_sync_conflicts as c
    set resolved_at = coalesce(c.resolved_at, now())
    where c.conflict_id = p_conflict_id
      and c.user_id = v_user_id;

    return found;
end;
$$;

revoke all on function public.sync_claim_external_identity(text, text, text) from public, anon;
revoke all on function public.sync_apply_mutation_batch(jsonb) from public, anon;
revoke all on function public.sync_pull_delta(text, bigint, integer) from public, anon;
revoke all on function public.sync_get_cursor(text) from public, anon;
revoke all on function public.sync_snapshot_domain(text) from public, anon;
revoke all on function public.sync_get_mutation_conflicts(uuid) from public, anon;
revoke all on function public.sync_ack_conflict(bigint) from public, anon;

grant execute on function public.sync_claim_external_identity(text, text, text) to authenticated;
grant execute on function public.sync_apply_mutation_batch(jsonb) to authenticated;
grant execute on function public.sync_pull_delta(text, bigint, integer) to authenticated;
grant execute on function public.sync_get_cursor(text) to authenticated;
grant execute on function public.sync_snapshot_domain(text) to authenticated;
grant execute on function public.sync_get_mutation_conflicts(uuid) to authenticated;
grant execute on function public.sync_ack_conflict(bigint) to authenticated;
