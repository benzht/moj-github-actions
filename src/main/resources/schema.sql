drop table team if exists;

create table team (
	id bigint not null primary key auto_increment,
	name varchar(200) unique not null,
	password varchar(200),
	cpassword varchar(200),
	role varchar(200),
	company varchar(200),
	country varchar(200)
);

drop table result if exists;

create table result (
	id bigint not null primary key auto_increment,
	team_id bigint not null,
	assignment varchar(200) not null,
	score int default 0 not null,
	penalty int default 0,
	credit int default 100,
	FOREIGN KEY (team_id) REFERENCES team(id)
);

drop table test if exists;

create table test (
	id bigint not null primary key auto_increment,
	team_id bigint not null,
	assignment varchar(200) not null,
	test_name varchar(200) not null,
	success int default 0 not null,
	failure int default 0 not null,
	FOREIGN KEY (team_id) REFERENCES team(id)
);
	
