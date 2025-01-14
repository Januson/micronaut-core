package io.micronaut.aop.compile

import io.micronaut.aop.Intercepted
import io.micronaut.aop.InterceptorBinding
import io.micronaut.aop.InterceptorKind
import io.micronaut.aop.interceptors.Mutating
import io.micronaut.aop.simple.TestBinding
import io.micronaut.ast.transform.test.AbstractBeanDefinitionSpec
import io.micronaut.context.ApplicationContext
import io.micronaut.core.annotation.AnnotationValue
import io.micronaut.inject.AdvisedBeanType
import io.micronaut.inject.BeanDefinition
import io.micronaut.inject.BeanDefinitionReference
import io.micronaut.inject.annotation.NamedAnnotationMapper
import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.inject.writer.BeanDefinitionWriter
import spock.lang.Issue

import java.lang.annotation.Annotation

class AroundCompileSpec extends AbstractBeanDefinitionSpec {

    void 'test apply interceptor binder with annotation mapper'() {
        given:
        ApplicationContext context = buildContext('''
package mapperbinding;

import java.lang.annotation.*;
import io.micronaut.aop.*;
import jakarta.inject.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Singleton
class MyBean {
    @TestAnn
    void test() {

    }

}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@interface TestAnn {
}

@InterceptorBean(TestAnn.class)
class TestInterceptor implements Interceptor {
    boolean invoked = false;
    @Override
    public Object intercept(InvocationContext context) {
        invoked = true;
        return context.proceed();
    }
}

''')
        def instance = getBean(context, 'mapperbinding.MyBean')
        def interceptor = getBean(context, 'mapperbinding.TestInterceptor')

        when:
        instance.test()

        then:"the interceptor was invoked"
        instance instanceof Intercepted
        interceptor.invoked

    }

    void 'test method level interceptor matching'() {
        given:
        ApplicationContext context = buildContext('''
package annbinding2;

import java.lang.annotation.*;
import io.micronaut.aop.*;
import jakarta.inject.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import io.micronaut.aop.simple.*;

@Singleton
class MyBean {
    @TestAnn
    void test() {

    }

    @TestAnn2
    void test2() {

    }
}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Around
@interface TestAnn {
}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Around
@interface TestAnn2 {
}

@InterceptorBean(TestAnn.class)
class TestInterceptor implements Interceptor {
    boolean invoked = false;
    @Override
    public Object intercept(InvocationContext context) {
        invoked = true;
        return context.proceed();
    }
}

@InterceptorBean(TestAnn2.class)
class AnotherInterceptor implements Interceptor {
    boolean invoked = false;
    @Override
    public Object intercept(InvocationContext context) {
        invoked = true;
        return context.proceed();
    }
}
''')
        def instance = getBean(context, 'annbinding2.MyBean')
        def interceptor = getBean(context, 'annbinding2.TestInterceptor')
        def anotherInterceptor = getBean(context, 'annbinding2.AnotherInterceptor')

        when:
        instance.test()

        then:"the interceptor was invoked"
        instance instanceof Intercepted
        interceptor.invoked
        !anotherInterceptor.invoked

        when:
        instance.test2()

        then:
        anotherInterceptor.invoked


        cleanup:
        context.close()
    }

    void 'test annotation with just interceptor binding'() {
        given:
        ApplicationContext context = buildContext('''
package annbinding1;

import java.lang.annotation.*;
import io.micronaut.aop.*;
import jakarta.inject.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Singleton
@TestAnn
class MyBean {
    void test() {
    }
}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Around
@interface TestAnn {
}

@InterceptorBean(TestAnn.class)
class TestInterceptor implements Interceptor {
    boolean invoked = false;
    @Override
    public Object intercept(InvocationContext context) {
        invoked = true;
        return context.proceed();
    }
}

@Singleton
class AnotherInterceptor implements Interceptor {
    boolean invoked = false;
    @Override
    public Object intercept(InvocationContext context) {
        invoked = true;
        return context.proceed();
    }
}
''')
        def instance = getBean(context, 'annbinding1.MyBean')
        def interceptor = getBean(context, 'annbinding1.TestInterceptor')
        def anotherInterceptor = getBean(context, 'annbinding1.AnotherInterceptor')
        instance.test()

        expect:"the interceptor was invoked"
        instance instanceof Intercepted
        interceptor.invoked
        !anotherInterceptor.invoked

        cleanup:
        context.close()
    }

    void 'test annotation with just around'() {
        given:
        ApplicationContext context = buildContext('''
package justaround;

import java.lang.annotation.*;
import io.micronaut.aop.*;
import jakarta.inject.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Singleton
@TestAnn
class MyBean {
    void test() {
    }
}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Around
@interface TestAnn {
}

@InterceptorBean(TestAnn.class)
class TestInterceptor implements Interceptor {
    boolean invoked = false;
    @Override
    public Object intercept(InvocationContext context) {
        invoked = true;
        return context.proceed();
    }
}

@Singleton
class AnotherInterceptor implements Interceptor {
    boolean invoked = false;
    @Override
    public Object intercept(InvocationContext context) {
        invoked = true;
        return context.proceed();
    }
}
''')
        def instance = getBean(context, 'justaround.MyBean')
        def interceptor = getBean(context, 'justaround.TestInterceptor')
        def anotherInterceptor = getBean(context, 'justaround.AnotherInterceptor')
        instance.test()

        expect:"the interceptor was invoked"
        instance instanceof Intercepted
        interceptor.invoked
        !anotherInterceptor.invoked

        cleanup:
        context.close()
    }

    @Issue('https://github.com/micronaut-projects/micronaut-core/issues/5522')
    void 'test Around annotation on private method fails'() {
        when:
        buildContext('''
package around.priv.method;

import java.lang.annotation.*;
import io.micronaut.aop.*;
import jakarta.inject.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Singleton
class MyBean {
    @TestAnn
    private void foobar() {
    }
}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Around
@interface TestAnn {
}
''')

        then:
        Throwable t = thrown()
        t.message.contains 'Method annotated as executable but is declared private'
    }

    @Issue('https://github.com/micronaut-projects/micronaut-core/issues/5522')
    void 'based on http-client StreamSpec; allow private method with Executable stereotype as long as not declared'() {
        when:
        buildContext('''
package stream.spec;

import io.micronaut.http.annotation.*;

    @Controller('/stream')
    class StreamEchoController {
        private static String helper(String s) {
            s.toUpperCase()
        }
    }
''')

        then:
        noExceptionThrown()
    }

    void 'test byte[] return compile'() {
        given:
        ApplicationContext context = buildContext('''
package aroundctest1;

import io.micronaut.aop.proxytarget.*;

@jakarta.inject.Singleton
@Mutating("someVal")
class MyBean {
    byte[] test(byte[] someVal) {
        return null;
    };
}
''')
        def instance = getBean(context, 'aroundctest1.MyBean')
        expect:
        instance != null

        cleanup:
        context.close()
    }

    void 'compile simple AOP advice'() {
        given:
        BeanDefinition beanDefinition = buildInterceptedBeanDefinition('aroundctest2.MyBean', '''
package aroundctest2;

import io.micronaut.aop.interceptors.*;
import io.micronaut.aop.simple.*;

@jakarta.inject.Singleton
@Mutating("someVal")
@TestBinding
class MyBean {
    void test() {};
}
''')

        BeanDefinitionReference ref = buildInterceptedBeanDefinitionReference('aroundctest3.MyBean', '''
package aroundctest3;

import io.micronaut.aop.interceptors.*;
import io.micronaut.aop.simple.*;

@jakarta.inject.Singleton
@Mutating("someVal")
@TestBinding
class MyBean {
    void test() {};
}
''')

        def annotationMetadata = beanDefinition?.annotationMetadata
        def values = annotationMetadata.getAnnotationValuesByType(InterceptorBinding)

        expect:
        values.size() == 2
        values[0].stringValue().get() == Mutating.name
        values[0].enumValue("kind", InterceptorKind).get() == InterceptorKind.AROUND
        values[0].classValue("interceptorType").isPresent()
        values[1].stringValue().get() == TestBinding.name
        !values[1].classValue("interceptorType").isPresent()
        values[1].enumValue("kind", InterceptorKind).get() == InterceptorKind.AROUND
        beanDefinition != null
        beanDefinition instanceof AdvisedBeanType
        beanDefinition.interceptedType.name == 'aroundctest2.MyBean'
        ref in AdvisedBeanType
        ref.interceptedType.name == 'aroundctest3.MyBean'
    }

    void 'test multiple annotations on a single method'() {
        given:
        ApplicationContext context = buildContext('''
package annbinding2;

import java.lang.annotation.*;
import io.micronaut.aop.*;
import jakarta.inject.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import io.micronaut.aop.simple.*;

@Singleton
class MyBean {
    @TestAnn
    @TestAnn2
    void test() {

    }

}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Around
@interface TestAnn {
}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Around
@interface TestAnn2 {
}

@InterceptorBean(TestAnn.class)
class TestInterceptor implements Interceptor {
    boolean invoked = false;
    @Override
    public Object intercept(InvocationContext context) {
        invoked = true;
        return context.proceed();
    }
}

@InterceptorBean(TestAnn2.class)
class AnotherInterceptor implements Interceptor {
    boolean invoked = false;
    @Override
    public Object intercept(InvocationContext context) {
        invoked = true;
        return context.proceed();
    }
}
''')
        def instance = getBean(context, 'annbinding2.MyBean')
        def interceptor = getBean(context, 'annbinding2.TestInterceptor')
        def anotherInterceptor = getBean(context, 'annbinding2.AnotherInterceptor')

        when:
        instance.test()

        then:"the interceptor was invoked"
        instance instanceof Intercepted
        interceptor.invoked
        anotherInterceptor.invoked

        cleanup:
        context.close()
    }

    void 'test multiple interceptor binding'() {
        given:
        ApplicationContext context = buildContext('''
package multiplebinding;

import java.lang.annotation.*;
import io.micronaut.aop.*;
import io.micronaut.context.annotation.NonBinding;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import jakarta.inject.Singleton;

@Retention(RUNTIME)
@InterceptorBinding(kind = InterceptorKind.AROUND)
@interface Deadly {

}

@Retention(RUNTIME)
@InterceptorBinding(kind = InterceptorKind.AROUND)
@interface Fast {
}

@Retention(RUNTIME)
@InterceptorBinding(kind = InterceptorKind.AROUND)
@interface Slow {
}

@UFO
@Inherited
@Retention(RUNTIME)
@InterceptorBinding(kind = InterceptorKind.AROUND)
@interface MissileAnn {
}

@Inherited
@Retention(RUNTIME)
@InterceptorBinding(kind = InterceptorKind.AROUND)
@interface UFO {
}

@MissileAnn
interface Missile {
    void fire();
}

@Fast
@Deadly
@Singleton
class FastAndDeadlyMissile implements Missile {
    public void fire() {
    }
}

@Deadly
@Singleton
class DeadlyMissile implements Missile {
    public void fire() {
    }
}

@Deadly
@Singleton
class GuidedMissile implements Missile {

    @Slow
    public void lockAndFire() {
    }

    @Fast
    public void fire() {
    }

}

@Slow
@Deadly
@Singleton
class SlowMissile implements Missile {
    public void fire() {
    }
}

@Fast
@Deadly
@MissileAnn
@Singleton
class FastDeadlyInterceptor implements MethodInterceptor<Object, Object> {
    public boolean intercepted = false;

    @Override public Object intercept(MethodInvocationContext<Object, Object> context) {
        intercepted = true;
        return context.proceed();
    }
}

@Slow
@Deadly
@MissileAnn
@Singleton
class SlowDeadlyInterceptor implements MethodInterceptor<Object, Object> {
    public boolean intercepted = false;

    @Override public Object intercept(MethodInvocationContext<Object, Object> context) {
        intercepted = true;
        return context.proceed();
    }
}

@Deadly
@UFO
@Singleton
class DeadlyInterceptor implements MethodInterceptor<Object, Object> {
    public boolean intercepted = false;

    @Override public Object intercept(MethodInvocationContext<Object, Object> context) {
        intercepted = true;
        return context.proceed();
    }

    public void reset() {
        intercepted = false;
    }
}

@UFO
@Singleton
class UFOInterceptor implements MethodInterceptor<Object, Object> {
    public boolean intercepted = false;

    @Override public Object intercept(MethodInvocationContext<Object, Object> context) {
        intercepted = true;
        return context.proceed();
    }

    public void reset() {
        intercepted = false;
    }
}

''')
        def fastDeadlyInterceptor = getBean(context, 'multiplebinding.FastDeadlyInterceptor')
        def slowDeadlyInterceptor = getBean(context, 'multiplebinding.SlowDeadlyInterceptor')
        def deadlyInterceptor = getBean(context, 'multiplebinding.DeadlyInterceptor')
        def ufoInterceptor = getBean(context, 'multiplebinding.UFOInterceptor')

        when:
        fastDeadlyInterceptor.intercepted = false
        slowDeadlyInterceptor.intercepted = false
        deadlyInterceptor.intercepted = false
        ufoInterceptor.intercepted = false
        def guidedMissile = getBean(context, 'multiplebinding.GuidedMissile');
        guidedMissile.fire()

        then:
        fastDeadlyInterceptor.intercepted
        !slowDeadlyInterceptor.intercepted
        deadlyInterceptor.intercepted
        ufoInterceptor.intercepted

        when:
        fastDeadlyInterceptor.intercepted = false
        slowDeadlyInterceptor.intercepted = false
        deadlyInterceptor.intercepted = false
        ufoInterceptor.intercepted = false
        guidedMissile = getBean(context, 'multiplebinding.GuidedMissile');
        guidedMissile.lockAndFire()

        then:
        !fastDeadlyInterceptor.intercepted
        slowDeadlyInterceptor.intercepted
        deadlyInterceptor.intercepted
        ufoInterceptor.intercepted

        when:
        fastDeadlyInterceptor.intercepted = false
        slowDeadlyInterceptor.intercepted = false
        deadlyInterceptor.intercepted = false
        ufoInterceptor.intercepted = false
        def fastAndDeadlyMissile = getBean(context, 'multiplebinding.FastAndDeadlyMissile');
        fastAndDeadlyMissile.fire()

        then:
        fastDeadlyInterceptor.intercepted
        !slowDeadlyInterceptor.intercepted
        deadlyInterceptor.intercepted
        ufoInterceptor.intercepted

        when:
        fastDeadlyInterceptor.intercepted = false
        slowDeadlyInterceptor.intercepted = false
        deadlyInterceptor.intercepted = false
        ufoInterceptor.intercepted = false
        def slowMissile = getBean(context, 'multiplebinding.SlowMissile');
        slowMissile.fire()

        then:
        !fastDeadlyInterceptor.intercepted
        slowDeadlyInterceptor.intercepted
        deadlyInterceptor.intercepted
        ufoInterceptor.intercepted

        when:
        fastDeadlyInterceptor.intercepted = false
        slowDeadlyInterceptor.intercepted = false
        deadlyInterceptor.intercepted = false
        ufoInterceptor.intercepted = false
        def anyMissile = getBean(context, 'multiplebinding.DeadlyMissile');
        anyMissile.fire()

        then:
        !fastDeadlyInterceptor.intercepted
        !slowDeadlyInterceptor.intercepted
        deadlyInterceptor.intercepted
        ufoInterceptor.intercepted

        cleanup:
        context.close()
    }

    void 'test multiple annotations on an interceptor and method'() {
        given:
        ApplicationContext context = buildContext('''
package annbinding2;

import java.lang.annotation.*;
import io.micronaut.aop.*;
import jakarta.inject.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import io.micronaut.aop.simple.*;

@Singleton
class MyBean {

    @TestAnn
    @TestAnn2
    void test() {

    }

}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Around
@interface TestAnn {
}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Around
@interface TestAnn2 {
}


@InterceptorBean([ TestAnn.class, TestAnn2.class ])
class TestInterceptor implements Interceptor {
    long count = 0;
    @Override
    public Object intercept(InvocationContext context) {
        count++;
        return context.proceed();
    }
}
''')
        def instance = getBean(context, 'annbinding2.MyBean')
        def interceptor = getBean(context, 'annbinding2.TestInterceptor')

        when:
        instance.test()

        then:
        interceptor.count == 1

        cleanup:
        context.close()
    }

    void 'test multiple annotations on an interceptor'() {
        given:
        ApplicationContext context = buildContext('''
package annbinding2;

import java.lang.annotation.*;
import io.micronaut.aop.*;
import jakarta.inject.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import io.micronaut.aop.simple.*;

@Singleton
class MyBean {

    @TestAnn
    void test() {
    }

    @TestAnn2
    void test2() {
    }

    @TestAnn
    @TestAnn2
    void testBoth() {
    }

}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Around
@interface TestAnn {
}

@Retention(RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Around
@interface TestAnn2 {
}


@InterceptorBean([TestAnn.class, TestAnn2.class])
class TestInterceptor implements Interceptor {
    long count = 0;
    @Override
    public Object intercept(InvocationContext context) {
        count++;
        return context.proceed();
    }
}
''')
        def instance = getBean(context, 'annbinding2.MyBean')
        def interceptor = getBean(context, 'annbinding2.TestInterceptor')

        when:
        instance.test()

        then:
        interceptor.count == 0

        when:
        instance.test2()

        then:
        interceptor.count == 0

        when:
        instance.testBoth()

        then:
        interceptor.count == 1

        cleanup:
        context.close()
    }

    void "test validated on class with generics"() {
        when:
        BeanDefinition beanDefinition = buildBeanDefinition('aroundctest4.$BaseEntityService' + BeanDefinitionWriter.CLASS_SUFFIX + BeanDefinitionWriter.PROXY_SUFFIX, """
package aroundctest4;

@io.micronaut.validation.Validated
class BaseEntityService<T extends BaseEntity> extends BaseService<T> {
}

class BaseEntity {}
abstract class BaseService<T> implements IBeanValidator<T> {
    public boolean isValid(T entity) {
        return true;
    }
}
interface IBeanValidator<T> {
    boolean isValid(T entity);
}
""")

        then:
        noExceptionThrown()
        beanDefinition != null
        beanDefinition.getTypeArguments('aroundctest4.BaseService')[0].type.name == 'aroundctest4.BaseEntity'
    }

    static class NamedTestAnnMapper implements NamedAnnotationMapper {

        @Override
        String getName() {
            return 'mapperbinding.TestAnn'
        }

        @Override
        List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
            return Collections.singletonList(AnnotationValue.builder(InterceptorBinding)
                    .value(getName())
                    .member("kind", InterceptorKind.AROUND)
                    .build())
        }
    }
}

