package org.novasparkle.acrux.util;

import lombok.Getter;
import lombok.experimental.UtilityClass;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import java.io.File;

@UtilityClass
public class HibernateUtil {
    @Getter
    private SessionFactory sessionFactory;

    public void buildSessionFactory(File file) {
        try {
            sessionFactory = new Configuration().configure(file).buildSessionFactory();
        } catch (Throwable ex) {
            System.err.println("Initial SessionFactory creation failed: " + ex);
            throw new ExceptionInInitializerError(ex);
        }
    }

    public Session openSession() {
        return sessionFactory.openSession();
    }

    public void shutdown() {
        getSessionFactory().close();
    }
}
