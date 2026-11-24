package com.smartparking.config;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Bootstrap implements ApplicationRunner {
    private final JdbcClient db; private final PasswordEncoder encoder;
    @Value("${app.bootstrap.email}") String email;
    @Value("${app.bootstrap.password}") String password;
    public Bootstrap(JdbcClient db, PasswordEncoder encoder) { this.db=db; this.encoder=encoder; }
    @Override @Transactional public void run(ApplicationArguments args) {
        // Never create demo users implicitly; operator supplies a one-time strong credential.
        if(email.isBlank() || password.isBlank()) return;
        com.smartparking.modules.auth.application.AuthService.validatePassword(password);
        db.sql("SELECT pg_advisory_xact_lock(7812345)").query().singleRow();
        if(db.sql("SELECT count(*) FROM users").query(Long.class).single()>0) return;
        UUID user=UUID.randomUUID(),org=UUID.randomUUID(),branch=UUID.randomUUID();
        db.sql("INSERT INTO users(id,email,password_hash,full_name) VALUES(:id,:email,:hash,'Administrador inicial')").param("id",user).param("email",email.toLowerCase(Locale.ROOT)).param("hash",encoder.encode(password)).update();
        db.sql("INSERT INTO organizations(id,name) VALUES(:id,'Mi organización')").param("id",org).update();
        db.sql("INSERT INTO branches(id,organization_id,code,name) VALUES(:id,:org,'PRINCIPAL','Sede principal')").param("id",branch).param("org",org).update();
        db.sql("INSERT INTO user_memberships(id,user_id,organization_id,role) VALUES(:id,:user,:org,'SUPERADMIN')").param("id",UUID.randomUUID()).param("user",user).param("org",org).update();
    }
}
