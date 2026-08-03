-- S6 adds Order.stripeSessionId (nullable, unique) to persist the Stripe Checkout Session id created
-- for an order. ddl-auto=validate only checks column existence/type/nullability (not uniqueness), so
-- the plain ADD COLUMN IF NOT EXISTS is what satisfies Hibernate here -- the UNIQUE constraint below is
-- for real DB-level integrity (S7's webhook handler looks orders up by stripe_session_id and must find
-- at most one row).
ALTER TABLE orders ADD COLUMN IF NOT EXISTS stripe_session_id varchar(255);

-- Postgres has no "ADD CONSTRAINT IF NOT EXISTS" (only DROP CONSTRAINT IF EXISTS is valid syntax), so
-- guard with a DO block the same way V2 does, checking pg_constraint before adding -- keeps this
-- migration a no-op on a volume where it already ran.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace n ON n.oid = rel.relnamespace AND n.nspname = current_schema()
        WHERE rel.relname = 'orders'
          AND con.conname = 'orders_stripe_session_id_key'
    ) THEN
        ALTER TABLE orders ADD CONSTRAINT orders_stripe_session_id_key UNIQUE (stripe_session_id);
    END IF;
END $$;
