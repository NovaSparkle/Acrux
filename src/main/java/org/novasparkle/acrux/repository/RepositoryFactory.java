package org.novasparkle.acrux.repository;

import lombok.experimental.UtilityClass;

import java.lang.reflect.Proxy;

@UtilityClass
public class RepositoryFactory {

    @SuppressWarnings("all")
    public <T extends IRepository> T createRepositoryInstance(Class<?> repositoryInterface) {
        if (!IRepository.class.isAssignableFrom(repositoryInterface))
            throw new IllegalArgumentException(repositoryInterface.getName() + " должен реализовать " + IRepository.class.getSimpleName() + "!");

        return (T) Proxy.newProxyInstance(
                repositoryInterface.getClassLoader(),
                new Class[]{repositoryInterface},
                new RepositoryInvocationHandler(repositoryInterface)
        );
    }
}
