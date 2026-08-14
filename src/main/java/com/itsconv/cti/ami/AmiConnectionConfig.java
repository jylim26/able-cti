package com.itsconv.cti.ami;

import org.asteriskjava.manager.ManagerConnection;
import org.asteriskjava.manager.ManagerConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AmiConnectionConfig {

    @Bean
    ManagerConnection managerConnection(AmiProperties props) {
        return new ManagerConnectionFactory(props.host(), props.port(), props.username(), props.password())
                .createManagerConnection();
    }
}
