-- Fixed list of spending categories, chosen by the sender when sending money to someone else.
CREATE TABLE categories (
    id         UUID        PRIMARY KEY,
    name       VARCHAR(40) NOT NULL UNIQUE,
    sort_order INT         NOT NULL
);

INSERT INTO categories (id, name, sort_order) VALUES
    (gen_random_uuid(), 'Groceries',         1),
    (gen_random_uuid(), 'Housing',           2),
    (gen_random_uuid(), 'Transport',         3),
    (gen_random_uuid(), 'Dining',            4),
    (gen_random_uuid(), 'Shopping',          5),
    (gen_random_uuid(), 'Bills & utilities', 6),
    (gen_random_uuid(), 'Entertainment',     7),
    (gen_random_uuid(), 'Health',            8),
    (gen_random_uuid(), 'Travel',            9),
    (gen_random_uuid(), 'Other',            10);

-- Null means "uncategorized" (deposits, received money, own-account moves, and older transfers).
ALTER TABLE transactions ADD COLUMN category_id UUID REFERENCES categories (id);
