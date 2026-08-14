CREATE TABLE queue_log (
    id        bigserial PRIMARY KEY,
    time      varchar(32),
    callid    varchar(150),
    queuename varchar(128),
    agent     varchar(128),
    event     varchar(32),
    data1     varchar(256),
    data2     varchar(256),
    data3     varchar(256),
    data4     varchar(256),
    data5     varchar(256)
);

CREATE INDEX idx_queue_log_callid ON queue_log (callid);
CREATE INDEX idx_queue_log_queuename ON queue_log (queuename);

CREATE TABLE queues (
    name            varchar(128) PRIMARY KEY,
    strategy        varchar(32)  NOT NULL DEFAULT 'rrmemory',
    timeout         integer      NOT NULL DEFAULT 15,
    retry           integer      NOT NULL DEFAULT 2,
    wrapuptime      integer      NOT NULL DEFAULT 5,
    maxlen          integer      NOT NULL DEFAULT 0,
    servicelevel    integer      NOT NULL DEFAULT 20,
    weight          integer      NOT NULL DEFAULT 0,
    musiconhold     varchar(128) NOT NULL DEFAULT 'default',
    joinempty       varchar(32)  NOT NULL DEFAULT 'yes',
    leavewhenempty  varchar(32)  NOT NULL DEFAULT 'no',
    ringinuse       varchar(8)   NOT NULL DEFAULT 'no'
);

INSERT INTO queues (name) VALUES ('queue01');

CREATE TABLE ps_endpoints (
    id              varchar(40) PRIMARY KEY,
    transport       varchar(40),
    aors            varchar(200),
    auth            varchar(40),
    context         varchar(40),
    disallow        varchar(200),
    allow           varchar(200),
    direct_media    varchar(3),
    callerid        varchar(80),
    force_rport     varchar(3),
    rewrite_contact varchar(3),
    rtp_symmetric   varchar(3)
);

CREATE TABLE ps_auths (
    id        varchar(40) PRIMARY KEY,
    auth_type varchar(8),
    username  varchar(40),
    password  varchar(80)
);

CREATE TABLE ps_aors (
    id                varchar(40) PRIMARY KEY,
    max_contacts      integer,
    remove_existing   varchar(3),
    qualify_frequency integer
);

-- 개발용
INSERT INTO ps_endpoints (id, aors, auth, context, disallow, allow, direct_media, callerid) VALUES
    ('1000', '1000', '1000', 'from-internal', 'all', 'ulaw,alaw', 'no', 'Agent 1000 <1000>'),
    ('1001', '1001', '1001', 'from-internal', 'all', 'ulaw,alaw', 'no', 'Agent 1001 <1001>'),
    ('1234', '1234', '1234', 'from-trunk',    'all', 'ulaw,alaw', 'no', 'Customer <01012345678>');

INSERT INTO ps_auths (id, auth_type, username, password) VALUES
    ('1000', 'userpass', '1000', '1000pass'),
    ('1001', 'userpass', '1001', '1001pass'),
    ('1234', 'userpass', '1234', '1234pass');

INSERT INTO ps_aors (id, max_contacts, remove_existing) VALUES
    ('1000', 1, 'yes'),
    ('1001', 1, 'yes'),
    ('1234', 1, 'yes');
