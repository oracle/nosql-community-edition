CREATE TABLE Foo(
    id LONG GENERATED ALWAYS AS IDENTITY (NO CACHE NO CACHE),
    name STRING,
    PRIMARY KEY (id)
)
