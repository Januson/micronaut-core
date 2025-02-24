package io.micronaut.inject.configproperties

import io.micronaut.ast.transform.test.AbstractBeanDefinitionSpec
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Property
import io.micronaut.context.exceptions.NoSuchBeanException
import io.micronaut.inject.BeanDefinition
import io.micronaut.inject.InstantiatableBeanDefinition
import io.micronaut.inject.ValidatedBeanDefinition
import io.micronaut.runtime.context.env.ConfigurationAdvice

class InterfaceConfigurationPropertiesSpec extends AbstractBeanDefinitionSpec {


    void "test simple interface config props"() {

        when:
        BeanDefinition beanDefinition = buildBeanDefinition('test.MyConfig$Intercepted', '''
package test;

import io.micronaut.context.annotation.*;
import java.time.Duration;

@ConfigurationProperties("foo.bar")
interface MyConfig {
    @jakarta.validation.constraints.NotBlank
    String getHost();

    @jakarta.validation.constraints.Min(10L)
    int getServerPort();
}

''')
        then:
        beanDefinition instanceof ValidatedBeanDefinition
        beanDefinition.getRequiredMethod("getHost")
                .stringValue(Property, "name").get() == 'foo.bar.host'
        beanDefinition.getRequiredMethod("getServerPort")
                .stringValue(Property, "name").get() == 'foo.bar.server-port'

        when:
        def context = ApplicationContext.run('foo.bar.host': 'test', 'foo.bar.server-port': '9999')
        def config = ((InstantiatableBeanDefinition) beanDefinition).instantiate(context)

        then:
        config.host == 'test'
        config.serverPort == 9999

        cleanup:
        context.close()

    }

    void "test optional interface config props"() {

        when:
        BeanDefinition beanDefinition = buildBeanDefinition('test.MyConfig$Intercepted', '''
package test;

import io.micronaut.context.annotation.*;
import java.net.URL;
import java.util.Optional;

@ConfigurationProperties("foo.bar")
@Executable
interface MyConfig {
    @javax.annotation.Nullable
    String getHost();

    @jakarta.validation.constraints.Min(10L)
    Optional<Integer> getServerPort();

    @io.micronaut.core.bind.annotation.Bindable(defaultValue = "http://default")
    Optional<URL> getURL();
}

''')
        then:
        beanDefinition.getAnnotationMetadata().getAnnotationType(ConfigurationAdvice.class.getName()).isPresent()
        beanDefinition instanceof ValidatedBeanDefinition
        beanDefinition.getRequiredMethod("getHost")
                .stringValue(Property, "name").get() == 'foo.bar.host'
        beanDefinition.getRequiredMethod("getServerPort")
                .stringValue(Property, "name").get() == 'foo.bar.server-port'
        beanDefinition.getRequiredMethod("getURL")
                .stringValue(Property, "name").get() == 'foo.bar.url'

        when:
        def context = ApplicationContext.run()
        def config = ((InstantiatableBeanDefinition) beanDefinition).instantiate(context)

        then:
        config.host == null
        config.serverPort == Optional.empty()
        config.URL == Optional.of(new URL("http://default"))

        when:
        def context2 = ApplicationContext.run('foo.bar.host': 'test', 'foo.bar.server-port': '9999', 'foo.bar.url': 'http://test')
        def config2 = ((InstantiatableBeanDefinition) beanDefinition).instantiate(context2)

        then:
        config2.host == 'test'
        config2.serverPort == Optional.of(9999)
        config2.URL == Optional.of(new URL("http://test"))

        cleanup:
        context.close()
        context2.close()

    }

    void "test inheritance interface config props"() {

        when:
        BeanDefinition beanDefinition = buildBeanDefinition('test.MyConfig$Intercepted', '''
package test;

import io.micronaut.context.annotation.*;
import java.time.Duration;

@ConfigurationProperties("bar")
@Executable
interface MyConfig extends ParentConfig {

    @jakarta.validation.constraints.Min(10L)
    int getServerPort();
}

@ConfigurationProperties("foo")
@Executable
interface ParentConfig {
    @jakarta.validation.constraints.NotBlank
    String getHost();
}

''')
        then:
        beanDefinition instanceof ValidatedBeanDefinition
        beanDefinition.getRequiredMethod("getServerPort")
                .stringValue(Property, "name").get() == 'foo.bar.server-port'
        beanDefinition.getRequiredMethod("getHost")
                .stringValue(Property, "name").get() == 'foo.bar.host'

        when:
        def context = ApplicationContext.run('foo.bar.host': 'test', 'foo.bar.server-port': '9999')
        def config = ((InstantiatableBeanDefinition) beanDefinition).instantiate(context)

        then:
        config.host == 'test'
        config.serverPort == 9999

        cleanup:
        context.close()

    }

    void "test nested interface config props"() {

        when:
        BeanDefinition beanDefinition = buildBeanDefinition('test.MyConfig$ChildConfig$Intercepted', '''
package test;

import io.micronaut.context.annotation.*;
import java.time.Duration;
import java.net.URL;

@ConfigurationProperties("foo.bar")
@Executable
interface MyConfig {
    @jakarta.validation.constraints.NotBlank
    String getHost();

    @jakarta.validation.constraints.Min(10L)
    int getServerPort();

    @ConfigurationProperties("child")
    static interface ChildConfig {
        URL getURL();
    }
}

''')
        def context = ApplicationContext.run('foo.bar.child.url': 'http://test')
        def config = ((InstantiatableBeanDefinition) beanDefinition).instantiate(context)

        then:
        config.URL == new URL("http://test")

        cleanup:
        context.close()

    }

    void "test nested interface config props - get child"() {

        when:
        BeanDefinition beanDefinition = buildBeanDefinition('test.MyConfig$Intercepted', '''
package test;

import io.micronaut.context.annotation.*;
import java.time.Duration;
import java.net.URL;

@ConfigurationProperties("foo.bar")
@Executable
interface MyConfig {
    @jakarta.validation.constraints.NotBlank
    String getHost();

    @jakarta.validation.constraints.Min(10L)
    int getServerPort();

    @Executable
    ChildConfig getChild();

    @ConfigurationProperties("child")
    static interface ChildConfig {
        @Executable
        URL getURL();
    }
}

''')
        then:
        beanDefinition instanceof BeanDefinition
        beanDefinition.getRequiredMethod("getChild")
                .isTrue(ConfigurationAdvice, "bean")

        when:
        def context = ApplicationContext.run('foo.bar.child.url': 'http://test')
        def config = ((InstantiatableBeanDefinition) beanDefinition).instantiate(context)
        config.child

        then:"we expect a bean resolution"
        def e = thrown(NoSuchBeanException)
        e.message.contains("No bean of type [test.MyConfig\$ChildConfig] exists")

        cleanup:
        context.close()

    }

    void "test invalid method"() {

        when:
        buildBeanDefinition('test.MyConfig$Intercepted', '''
package test;

import io.micronaut.context.annotation.*;
import java.time.Duration;

@ConfigurationProperties("foo.bar")
interface MyConfig {
    @jakarta.validation.constraints.NotBlank
    String junk(String s);

    @jakarta.validation.constraints.Min(10L)
    int getServerPort();
}

''')
        then:
        def e = thrown(RuntimeException)
        e.message.contains('Only getter methods are allowed on @ConfigurationProperties interfaces: test.MyConfig.junk(..). You can change the accessors using @AccessorsStyle annotation')
    }

    void "test getter that returns void method"() {

        when:
        buildBeanDefinition('test.MyConfig$Intercepted', '''
package test;

import io.micronaut.context.annotation.*;
import java.time.Duration;

@ConfigurationProperties("foo.bar")
interface MyConfig {
    void getServerPort();
}

''')
        then:
        def e = thrown(RuntimeException)
        e.message.contains('Getter methods must return a value @ConfigurationProperties interfaces')
    }
}
