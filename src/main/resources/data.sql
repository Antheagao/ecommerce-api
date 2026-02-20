-- Ensure order number sequence row exists (runs after JPA creates schema)
INSERT INTO order_number_seq (id, next_val)
SELECT 1, 1
WHERE NOT EXISTS (SELECT 1 FROM order_number_seq WHERE id = 1);
