-- S4 adds FAILED and REFUNDED to OrderStatus. V1's inline CHECK constraint on orders.status only
-- allows the original 5 values, so any INSERT/UPDATE using the new values would violate it --
-- ddl-auto=validate does not check constraint bodies, so this must be fixed via migration.
--
-- The constraint's name isn't reliable: on a fresh Postgres DB built by V1 it auto-names to
-- orders_status_check (unnamed CHECK inlined in CREATE TABLE), but on a legacy volume where
-- Hibernate's ddl-auto=update originally built the table, the constraint carries whatever name
-- Hibernate generated. The DO block below finds any CHECK constraint on orders.status by
-- inspecting pg_constraint/pg_get_constraintdef rather than assuming a name, drops it, then adds
-- the new one back under a known name -- safe to run on both fresh (post-V1) and legacy volumes.
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace n ON n.oid = rel.relnamespace AND n.nspname = current_schema()
        WHERE rel.relname = 'orders'
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) LIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;

ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'FAILED', 'REFUNDED'));
