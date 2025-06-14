create table if not exists bar1 (
    uid1 string as uuid,
    id integer,
    firstName string,
    lastName string,
    uid2 string as uuid,
    uid3 string as uuid,
    primary key(uid1, id, firstName)
)


create table if not exists bar2 (
    uid1 string as uuid,
    id integer,
    primary key(shard(uid1), id)
)


create index if not exists idx_bar1_str_uid2 on bar1 (firstName, uid2)


create table if not exists test1 (
    uid string as uuid,
    int  integer GENERATED ALWAYS AS IDENTITY
    (START WITH 2 INCREMENT BY 2 MAXVALUE 200 NO CYCLE),
    primary key(uid)
)


create table if not exists test2 (
    uid string as uuid,
    int  integer GENERATED ALWAYS AS IDENTITY
    (START WITH 2 INCREMENT BY 2 MAXVALUE 200 NO CYCLE),
    primary key(int)
)


create table if not exists test3 (
    uid string as uuid,
    int  integer GENERATED ALWAYS AS IDENTITY
    (START WITH 2 INCREMENT BY 2 MAXVALUE 200 NO CYCLE),
    primary key(uid,int)
)


create table if not exists test4 (
    uid1 string as uuid generated by default,
    uid2 string as uuid ,
    primary key(uid2)
)


create table if not exists test5 (
    uid1 string as uuid generated by default,
    uid2 string as uuid ,
    primary key(uid1)
)


create table if not exists test6 (
    uid1 string as uuid,
    id string,
    primary key(uid1)
)


ALTER TABLE test6 (ADD uid7  STRING AS UUID)


ALTER TABLE test6 (DROP uid7)


ALTER TABLE test6 (ADD uid8  STRING AS UUID GENERATED BY DEFAULT)


ALTER TABLE test6 (DROP uid8)

