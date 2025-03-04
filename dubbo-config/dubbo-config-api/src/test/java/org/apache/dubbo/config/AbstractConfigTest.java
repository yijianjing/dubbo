/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config;

import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.api.Greeting;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class AbstractConfigTest {

    @BeforeEach
    public void beforeEach() {
        DubboBootstrap.reset();
    }

    @AfterEach
    public void afterEach() {
        SysProps.clear();
    }

    //FIXME
    /*@Test
    public void testAppendProperties1() throws Exception {
        try {
            System.setProperty("dubbo.properties.i", "1");
            System.setProperty("dubbo.properties.c", "c");
            System.setProperty("dubbo.properties.b", "2");
            System.setProperty("dubbo.properties.d", "3");
            System.setProperty("dubbo.properties.f", "4");
            System.setProperty("dubbo.properties.l", "5");
            System.setProperty("dubbo.properties.s", "6");
            System.setProperty("dubbo.properties.str", "dubbo");
            System.setProperty("dubbo.properties.bool", "true");
            PropertiesConfig config = new PropertiesConfig();
            AbstractConfig.appendProperties(config);
            Assertions.assertEquals(1, config.getI());
            Assertions.assertEquals('c', config.getC());
            Assertions.assertEquals((byte) 0x02, config.getB());
            Assertions.assertEquals(3d, config.getD());
            Assertions.assertEquals(4f, config.getF());
            Assertions.assertEquals(5L, config.getL());
            Assertions.assertEquals(6, config.getS());
            Assertions.assertEquals("dubbo", config.getStr());
            Assertions.assertTrue(config.isBool());
        } finally {
            System.clearProperty("dubbo.properties.i");
            System.clearProperty("dubbo.properties.c");
            System.clearProperty("dubbo.properties.b");
            System.clearProperty("dubbo.properties.d");
            System.clearProperty("dubbo.properties.f");
            System.clearProperty("dubbo.properties.l");
            System.clearProperty("dubbo.properties.s");
            System.clearProperty("dubbo.properties.str");
            System.clearProperty("dubbo.properties.bool");
        }
    }

    @Test
    public void testAppendProperties2() throws Exception {
        try {
            System.setProperty("dubbo.properties.two.i", "2");
            PropertiesConfig config = new PropertiesConfig("two");
            AbstractConfig.appendProperties(config);
            Assertions.assertEquals(2, config.getI());
        } finally {
            System.clearProperty("dubbo.properties.two.i");
        }
    }

    @Test
    public void testAppendProperties3() throws Exception {
        try {
            Properties p = new Properties();
            p.put("dubbo.properties.str", "dubbo");
            ConfigUtils.setProperties(p);
            PropertiesConfig config = new PropertiesConfig();
            AbstractConfig.appendProperties(config);
            Assertions.assertEquals("dubbo", config.getStr());
        } finally {
            System.clearProperty(Constants.DUBBO_PROPERTIES_KEY);
            ConfigUtils.setProperties(null);
        }
    }*/

    @Test
    public void testValidateProtocolConfig() {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setCodec("exchange");
        protocolConfig.setName("test");
        protocolConfig.setHost("host");
        ConfigValidationUtils.validateProtocolConfig(protocolConfig);
    }

    @Test
    public void testAppendParameters1() throws Exception {
        Map<String, String> parameters = new HashMap<String, String>();
        parameters.put("num", "ONE");
        AbstractConfig.appendParameters(parameters, new ParameterConfig(1, "hello/world", 30, "password"), "prefix");
        Assertions.assertEquals("one", parameters.get("prefix.key.1"));
        Assertions.assertEquals("two", parameters.get("prefix.key.2"));
        Assertions.assertEquals("ONE,1", parameters.get("prefix.num"));
        Assertions.assertEquals("hello%2Fworld", parameters.get("prefix.naming"));
        Assertions.assertEquals("30", parameters.get("prefix.age"));
        Assertions.assertFalse(parameters.containsKey("prefix.secret"));
    }

    @Test
    public void testAppendParameters2() throws Exception {
        Assertions.assertThrows(IllegalStateException.class, () -> {
            Map<String, String> parameters = new HashMap<String, String>();
            AbstractConfig.appendParameters(parameters, new ParameterConfig());
        });
    }

    @Test
    public void testAppendParameters3() throws Exception {
        Map<String, String> parameters = new HashMap<String, String>();
        AbstractConfig.appendParameters(parameters, null);
        assertTrue(parameters.isEmpty());
    }

    @Test
    public void testAppendParameters4() throws Exception {
        Map<String, String> parameters = new HashMap<String, String>();
        AbstractConfig.appendParameters(parameters, new ParameterConfig(1, "hello/world", 30, "password"));
        Assertions.assertEquals("one", parameters.get("key.1"));
        Assertions.assertEquals("two", parameters.get("key.2"));
        Assertions.assertEquals("1", parameters.get("num"));
        Assertions.assertEquals("hello%2Fworld", parameters.get("naming"));
        Assertions.assertEquals("30", parameters.get("age"));
    }

    @Test
    public void testAppendAttributes1() throws Exception {
        ParameterConfig config = new ParameterConfig(1, "hello/world", 30, "password");
        Map<String, String> parameters = new HashMap<>();
        AbstractConfig.appendParameters(parameters, config);

        Map<String, String> attributes = new HashMap<>();
        AbstractConfig.appendAttributes(attributes, config);

        Assertions.assertEquals(null, parameters.get("secret"));
        Assertions.assertEquals(null, parameters.get("parameters"));
        // secret is excluded for url parameters, but keep for attributes
        Assertions.assertEquals(config.getSecret(), attributes.get("secret"));
        Assertions.assertEquals(config.getName(), attributes.get("name"));
        Assertions.assertEquals(""+config.getNumber(), attributes.get("number"));
        Assertions.assertEquals(""+config.getAge(), attributes.get("age"));
        Assertions.assertEquals(StringUtils.encodeParameters(config.getParameters()), attributes.get("parameters"));
    }

    @Test
    public void checkExtension() throws Exception {
        Assertions.assertThrows(IllegalStateException.class, () -> ConfigValidationUtils.checkExtension(ApplicationModel.defaultModel(), Greeting.class, "hello", "world"));
    }

    @Test
    public void checkMultiExtension1() throws Exception {
        Assertions.assertThrows(IllegalStateException.class,
                () -> ConfigValidationUtils.checkMultiExtension(ApplicationModel.defaultModel(), Greeting.class, "hello", "default,world"));
    }

    @Test
    public void checkMultiExtension2() throws Exception {
        Assertions.assertThrows(IllegalStateException.class,
                () -> ConfigValidationUtils.checkMultiExtension(ApplicationModel.defaultModel(), Greeting.class, "hello", "default,-world"));
    }

    @Test
    public void checkLength() throws Exception {
        Assertions.assertDoesNotThrow(() -> {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i <= 200; i++) {
                builder.append('a');
            }
            ConfigValidationUtils.checkLength("hello", builder.toString());
        });
    }

    @Test
    public void checkPathLength() throws Exception {
        Assertions.assertDoesNotThrow(() -> {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i <= 200; i++) {
                builder.append('a');
            }
            ConfigValidationUtils.checkPathLength("hello", builder.toString());
        });
    }

    @Test
    public void checkName() throws Exception {
        Assertions.assertDoesNotThrow(() -> ConfigValidationUtils.checkName("hello", "world%"));
    }

    @Test
    public void checkNameHasSymbol() throws Exception {
        try {
            ConfigValidationUtils.checkNameHasSymbol("hello", ":*,/ -0123\tabcdABCD");
            ConfigValidationUtils.checkNameHasSymbol("mock", "force:return world");
        } catch (Exception e) {
            fail("the value should be legal.");
        }
    }

    @Test
    public void checkKey() throws Exception {
        try {
            ConfigValidationUtils.checkKey("hello", "*,-0123abcdABCD");
        } catch (Exception e) {
            fail("the value should be legal.");
        }
    }

    @Test
    public void checkMultiName() throws Exception {
        try {
            ConfigValidationUtils.checkMultiName("hello", ",-._0123abcdABCD");
        } catch (Exception e) {
            fail("the value should be legal.");
        }
    }

    @Test
    public void checkPathName() throws Exception {
        try {
            ConfigValidationUtils.checkPathName("hello", "/-$._0123abcdABCD");
        } catch (Exception e) {
            fail("the value should be legal.");
        }
    }

    @Test
    public void checkMethodName() throws Exception {
        try {
            ConfigValidationUtils.checkMethodName("hello", "abcdABCD0123abcd");
        } catch (Exception e) {
            fail("the value should be legal.");
        }

        try {
            ConfigValidationUtils.checkMethodName("hello", "0a");
        } catch (Exception e) {
            // ignore
            fail("the value should be legal.");
        }
    }

    @Test
    public void checkParameterName() throws Exception {
        Map<String, String> parameters = Collections.singletonMap("hello", ":*,/-._0123abcdABCD");
        try {
            ConfigValidationUtils.checkParameterName(parameters);
        } catch (Exception e) {
            fail("the value should be legal.");
        }
    }

    @Test
    @Config(interfaceClass = Greeting.class, filter = {"f1, f2"}, listener = {"l1, l2"},
            parameters = {"k1", "v1", "k2", "v2"})
    public void appendAnnotation() throws Exception {
        Config config = getClass().getMethod("appendAnnotation").getAnnotation(Config.class);
        AnnotationConfig annotationConfig = new AnnotationConfig();
        annotationConfig.appendAnnotation(Config.class, config);
        Assertions.assertSame(Greeting.class, annotationConfig.getInterface());
        Assertions.assertEquals("f1, f2", annotationConfig.getFilter());
        Assertions.assertEquals("l1, l2", annotationConfig.getListener());
        Assertions.assertEquals(2, annotationConfig.getParameters().size());
        Assertions.assertEquals("v1", annotationConfig.getParameters().get("k1"));
        Assertions.assertEquals("v2", annotationConfig.getParameters().get("k2"));
        assertThat(annotationConfig.toString(), Matchers.containsString("filter=\"f1, f2\" "));
        assertThat(annotationConfig.toString(), Matchers.containsString("listener=\"l1, l2\" "));
    }

    @Test
    public void testRefreshAll() {
        try {
            OverrideConfig overrideConfig = new OverrideConfig();
            overrideConfig.setAddress("override-config://127.0.0.1:2181");
            overrideConfig.setProtocol("override-config");
            overrideConfig.setEscape("override-config://");
            overrideConfig.setExclude("override-config");

            Map<String, String> external = new HashMap<>();
            external.put("dubbo.override.address", "external://127.0.0.1:2181");
            // @Parameter(exclude=true)
            external.put("dubbo.override.exclude", "external");
            // @Parameter(key="key1", useKeyAsProperty=false)
            external.put("dubbo.override.key", "external");
            // @Parameter(key="key2", useKeyAsProperty=true)
            external.put("dubbo.override.key2", "external");
            ApplicationModel.defaultModel().getApplicationEnvironment().initialize();
            ApplicationModel.defaultModel().getApplicationEnvironment().setExternalConfigMap(external);

            SysProps.setProperty("dubbo.override.address", "system://127.0.0.1:2181");
            SysProps.setProperty("dubbo.override.protocol", "system");
            // this will not override, use 'key' instead, @Parameter(key="key1", useKeyAsProperty=false)
            SysProps.setProperty("dubbo.override.key1", "system");
            SysProps.setProperty("dubbo.override.key2", "system");

            // Load configuration from  system properties -> externalConfiguration -> RegistryConfig -> dubbo.properties
            overrideConfig.refresh();

            Assertions.assertEquals("system://127.0.0.1:2181", overrideConfig.getAddress());
            Assertions.assertEquals("system", overrideConfig.getProtocol());
            Assertions.assertEquals("override-config://", overrideConfig.getEscape());
            Assertions.assertEquals("external", overrideConfig.getKey());
            Assertions.assertEquals("system", overrideConfig.getKey2());
        } finally {
            SysProps.clear();
            ApplicationModel.defaultModel().getApplicationEnvironment().destroy();
        }
    }

    @Test
    public void testRefreshSystem() {
        try {
            OverrideConfig overrideConfig = new OverrideConfig();
            overrideConfig.setAddress("override-config://127.0.0.1:2181");
            overrideConfig.setProtocol("override-config");
            overrideConfig.setEscape("override-config://");
            overrideConfig.setExclude("override-config");

            SysProps.setProperty("dubbo.override.address", "system://127.0.0.1:2181");
            SysProps.setProperty("dubbo.override.protocol", "system");
            SysProps.setProperty("dubbo.override.key", "system");

            overrideConfig.refresh();

            Assertions.assertEquals("system://127.0.0.1:2181", overrideConfig.getAddress());
            Assertions.assertEquals("system", overrideConfig.getProtocol());
            Assertions.assertEquals("override-config://", overrideConfig.getEscape());
            Assertions.assertEquals("system", overrideConfig.getKey());
        } finally {
            SysProps.clear();
            ApplicationModel.defaultModel().getApplicationEnvironment().destroy();
        }
    }

    @Test
    public void testRefreshProperties() throws Exception {
        try {
            ApplicationModel.defaultModel().getApplicationEnvironment().setExternalConfigMap(new HashMap<>());
            OverrideConfig overrideConfig = new OverrideConfig();
            overrideConfig.setAddress("override-config://127.0.0.1:2181");
            overrideConfig.setProtocol("override-config");
            overrideConfig.setEscape("override-config://");

            Properties properties = new Properties();
            properties.load(this.getClass().getResourceAsStream("/dubbo.properties"));
            ConfigUtils.setProperties(properties);

            overrideConfig.refresh();

            Assertions.assertEquals("override-config://127.0.0.1:2181", overrideConfig.getAddress());
            Assertions.assertEquals("override-config", overrideConfig.getProtocol());
            Assertions.assertEquals("override-config://", overrideConfig.getEscape());
            //Assertions.assertEquals("properties", overrideConfig.getUseKeyAsProperty());
        } finally {
            ApplicationModel.defaultModel().getApplicationEnvironment().destroy();
            ConfigUtils.setProperties(null);
        }
    }

    @Test
    public void testRefreshExternal() {
        try {
            OverrideConfig overrideConfig = new OverrideConfig();
            overrideConfig.setAddress("override-config://127.0.0.1:2181");
            overrideConfig.setProtocol("override-config");
            overrideConfig.setEscape("override-config://");
            overrideConfig.setExclude("override-config");

            Map<String, String> external = new HashMap<>();
            external.put("dubbo.override.address", "external://127.0.0.1:2181");
            external.put("dubbo.override.protocol", "external");
            external.put("dubbo.override.escape", "external://");
            // @Parameter(exclude=true)
            external.put("dubbo.override.exclude", "external");
            // @Parameter(key="key1", useKeyAsProperty=false)
            external.put("dubbo.override.key", "external");
            // @Parameter(key="key2", useKeyAsProperty=true)
            external.put("dubbo.override.key2", "external");
            ApplicationModel.defaultModel().getApplicationEnvironment().initialize();
            ApplicationModel.defaultModel().getApplicationEnvironment().setExternalConfigMap(external);

            overrideConfig.refresh();

            Assertions.assertEquals("external://127.0.0.1:2181", overrideConfig.getAddress());
            Assertions.assertEquals("external", overrideConfig.getProtocol());
            Assertions.assertEquals("external://", overrideConfig.getEscape());
            Assertions.assertEquals("external", overrideConfig.getExclude());
            Assertions.assertEquals("external", overrideConfig.getKey());
            Assertions.assertEquals("external", overrideConfig.getKey2());
        } finally {
            ApplicationModel.defaultModel().getApplicationEnvironment().destroy();
        }
    }

    @Test
    public void testRefreshById() {
        try {
            OverrideConfig overrideConfig = new OverrideConfig();
            overrideConfig.setId("override-id");
            overrideConfig.setAddress("override-config://127.0.0.1:2181");
            overrideConfig.setProtocol("override-config");
            overrideConfig.setEscape("override-config://");
            overrideConfig.setExclude("override-config");

            Map<String, String> external = new HashMap<>();
            external.put("dubbo.overrides.override-id.address", "external-override-id://127.0.0.1:2181");
            external.put("dubbo.overrides.override-id.key", "external");
            external.put("dubbo.overrides.override-id.key2", "external");
            external.put("dubbo.override.address", "external://127.0.0.1:2181");
            external.put("dubbo.override.exclude", "external");
            ApplicationModel.defaultModel().getApplicationEnvironment().initialize();
            ApplicationModel.defaultModel().getApplicationEnvironment().setExternalConfigMap(external);

            // refresh config
            overrideConfig.refresh();

            Assertions.assertEquals("external-override-id://127.0.0.1:2181", overrideConfig.getAddress());
            Assertions.assertEquals("override-config", overrideConfig.getProtocol());
            Assertions.assertEquals("override-config://", overrideConfig.getEscape());
            Assertions.assertEquals("external", overrideConfig.getKey());
            Assertions.assertEquals("external", overrideConfig.getKey2());
        } finally {
            ApplicationModel.defaultModel().getApplicationEnvironment().destroy();
        }
    }

    @Test
    public void testRefreshParameters() {
        try {
            Map<String, String> parameters = new HashMap<>();
            parameters.put("key1", "value1");
            parameters.put("key2", "value2");
            OverrideConfig overrideConfig = new OverrideConfig();
            overrideConfig.setParameters(parameters);


            Map<String, String> external = new HashMap<>();
            external.put("dubbo.override.parameters", "[{key3:value3},{key4:value4},{key2:value5}]");
            ApplicationModel.defaultModel().getApplicationEnvironment().initialize();
            ApplicationModel.defaultModel().getApplicationEnvironment().setExternalConfigMap(external);

            // refresh config
            overrideConfig.refresh();

            Assertions.assertEquals("value1", overrideConfig.getParameters().get("key1"));
            Assertions.assertEquals("value5", overrideConfig.getParameters().get("key2"));
            Assertions.assertEquals("value3", overrideConfig.getParameters().get("key3"));
            Assertions.assertEquals("value4", overrideConfig.getParameters().get("key4"));

            SysProps.setProperty("dubbo.override.parameters", "[{key3:value6}]");
            overrideConfig.refresh();

            Assertions.assertEquals("value6", overrideConfig.getParameters().get("key3"));
            Assertions.assertEquals("value4", overrideConfig.getParameters().get("key4"));
        } finally {
            SysProps.clear();
            ApplicationModel.defaultModel().getApplicationEnvironment().destroy();
        }
    }

    @Test
    public void testRefreshParametersWithAttribute() {
        try {
            OverrideConfig overrideConfig = new OverrideConfig();
            SysProps.setProperty("dubbo.override.parameters.key00", "value00");
            overrideConfig.refresh();
            assertEquals("value00", overrideConfig.getParameters().get("key00"));
        } finally {
            SysProps.clear();
            ApplicationModel.defaultModel().getApplicationEnvironment().destroy();
        }
    }

    @Test
    public void testOnlyPrefixedKeyTakeEffect() {
        try {
            OverrideConfig overrideConfig = new OverrideConfig();
            overrideConfig.setNotConflictKey("value-from-config");

            Map<String, String> external = new HashMap<>();
            external.put("notConflictKey", "value-from-external");

            try {
                Map<String, String> map = new HashMap<>();
                map.put("notConflictKey", "value-from-env");
                map.put("dubbo.override.notConflictKey2", "value-from-env");
                setOsEnv(map);
            } catch (Exception e) {
                // ignore
                e.printStackTrace();
            }

            ApplicationModel.defaultModel().getApplicationEnvironment().setExternalConfigMap(external);

            overrideConfig.refresh();

            Assertions.assertEquals("value-from-config", overrideConfig.getNotConflictKey());
            Assertions.assertEquals("value-from-env", overrideConfig.getNotConflictKey2());
        } finally {
            ApplicationModel.defaultModel().getApplicationEnvironment().destroy();

        }
    }

    @Test
    public void tetMetaData() {
        OverrideConfig overrideConfig = new OverrideConfig();
        overrideConfig.setId("override-id");
        overrideConfig.setAddress("override-config://127.0.0.1:2181");
        overrideConfig.setProtocol("override-config");
        overrideConfig.setEscape("override-config://");
        overrideConfig.setExclude("override-config");

        Map<String, String> metaData = overrideConfig.getMetaData();
        Assertions.assertEquals("override-config://127.0.0.1:2181", metaData.get("address"));
        Assertions.assertEquals("override-config", metaData.get("protocol"));
        Assertions.assertEquals("override-config://", metaData.get("escape"));
        Assertions.assertEquals("override-config", metaData.get("exclude"));
        Assertions.assertNull(metaData.get("key"));
        Assertions.assertNull(metaData.get("key2"));
    }

    @Test
    public void testEquals() {
        ApplicationConfig application1 = new ApplicationConfig();
        ApplicationConfig application2 = new ApplicationConfig();
        application1.setName("app1");
        application2.setName("app2");
        Assertions.assertNotEquals(application1, application2);
        application1.setName("sameName");
        application2.setName("sameName");
        Assertions.assertEquals(application1, application2);

        ProtocolConfig protocol1 = new ProtocolConfig();
        protocol1.setName("dubbo");
        protocol1.setPort(1234);
        ProtocolConfig protocol2 = new ProtocolConfig();
        protocol2.setName("dubbo");
        protocol2.setPort(1235);
        Assertions.assertNotEquals(protocol1, protocol2);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.ANNOTATION_TYPE})
    public @interface ConfigField {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
    public @interface Config {
        Class<?> interfaceClass() default void.class;

        String interfaceName() default "";

        String[] filter() default {};

        String[] listener() default {};

        String[] parameters() default {};

        ConfigField[] configFields() default {};

        ConfigField configField() default @ConfigField;
    }

    private static class OverrideConfig extends AbstractConfig {
        public String address;
        public String protocol;
        public String exclude;
        public String key;
        public String key2;
        public String escape;
        public String notConflictKey;
        public String notConflictKey2;
        protected Map<String, String> parameters;

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        @Parameter(excluded = true)
        public String getExclude() {
            return exclude;
        }

        public void setExclude(String exclude) {
            this.exclude = exclude;
        }

        @Parameter(key = "key1")
        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        @Parameter(key = "mykey")
        public String getKey2() {
            return key2;
        }

        public void setKey2(String key2) {
            this.key2 = key2;
        }

        @Parameter(escaped = true)
        public String getEscape() {
            return escape;
        }

        public void setEscape(String escape) {
            this.escape = escape;
        }

        public String getNotConflictKey() {
            return notConflictKey;
        }

        public void setNotConflictKey(String notConflictKey) {
            this.notConflictKey = notConflictKey;
        }

        public String getNotConflictKey2() {
            return notConflictKey2;
        }

        public void setNotConflictKey2(String notConflictKey2) {
            this.notConflictKey2 = notConflictKey2;
        }

        public Map<String, String> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, String> parameters) {
            this.parameters = parameters;
        }
    }

    private static class PropertiesConfig extends AbstractConfig {
        private char c;
        private boolean bool;
        private byte b;
        private int i;
        private long l;
        private float f;
        private double d;
        private short s;
        private String str;

        PropertiesConfig() {
        }

        PropertiesConfig(String id) {
            this.setId(id);
        }

        public char getC() {
            return c;
        }

        public void setC(char c) {
            this.c = c;
        }

        public boolean isBool() {
            return bool;
        }

        public void setBool(boolean bool) {
            this.bool = bool;
        }

        public byte getB() {
            return b;
        }

        public void setB(byte b) {
            this.b = b;
        }

        public int getI() {
            return i;
        }

        public void setI(int i) {
            this.i = i;
        }

        public long getL() {
            return l;
        }

        public void setL(long l) {
            this.l = l;
        }

        public float getF() {
            return f;
        }

        public void setF(float f) {
            this.f = f;
        }

        public double getD() {
            return d;
        }

        public void setD(double d) {
            this.d = d;
        }

        public String getStr() {
            return str;
        }

        public void setStr(String str) {
            this.str = str;
        }

        public short getS() {
            return s;
        }

        public void setS(short s) {
            this.s = s;
        }
    }

    private static class ParameterConfig {
        private int number;
        private String name;
        private int age;
        private String secret;

        ParameterConfig() {
        }

        ParameterConfig(int number, String name, int age, String secret) {
            this.number = number;
            this.name = name;
            this.age = age;
            this.secret = secret;
        }

        @Parameter(key = "num", append = true)
        public int getNumber() {
            return number;
        }

        public void setNumber(int number) {
            this.number = number;
        }

        @Parameter(key = "naming", append = true, escaped = true, required = true)
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }

        @Parameter(excluded = true)
        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Map getParameters() {
            Map<String, String> map = new HashMap<String, String>();
            map.put("key.1", "one");
            map.put("key.2", "two");
            return map;
        }
    }

    private static class AnnotationConfig extends AbstractConfig {
        private Class interfaceClass;
        private String filter;
        private String listener;
        private Map<String, String> parameters;
        private String[] configFields;

        public Class getInterface() {
            return interfaceClass;
        }

        public void setInterface(Class interfaceName) {
            this.interfaceClass = interfaceName;
        }

        public String getFilter() {
            return filter;
        }

        public void setFilter(String filter) {
            this.filter = filter;
        }

        public String getListener() {
            return listener;
        }

        public void setListener(String listener) {
            this.listener = listener;
        }

        public Map<String, String> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, String> parameters) {
            this.parameters = parameters;
        }

        public String[] getConfigFields() {
            return configFields;
        }

        public void setConfigFields(String[] configFields) {
            this.configFields = configFields;
        }
    }

    protected static void setOsEnv(Map<String, String> newenv) throws Exception {
        try {
            Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
            Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
            theEnvironmentField.setAccessible(true);
            Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
            env.putAll(newenv);
            Field theCaseInsensitiveEnvironmentField = processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment");
            theCaseInsensitiveEnvironmentField.setAccessible(true);
            Map<String, String> cienv = (Map<String, String>) theCaseInsensitiveEnvironmentField.get(null);
            cienv.putAll(newenv);
        } catch (NoSuchFieldException e) {
            Class[] classes = Collections.class.getDeclaredClasses();
            Map<String, String> env = System.getenv();
            for (Class cl : classes) {
                if ("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
                    Field field = cl.getDeclaredField("m");
                    field.setAccessible(true);
                    Object obj = field.get(env);
                    Map<String, String> map = (Map<String, String>) obj;
                    map.clear();
                    map.putAll(newenv);
                }
            }
        }
    }


    @Test
    public void testMetaData() throws Exception {

        // Expect empty metadata for new instance
        // Check and set default value of field in checkDefault() method

        List<Class<? extends AbstractConfig>> configClasses = Arrays.asList(ApplicationConfig.class,
                ConsumerConfig.class, ProviderConfig.class, ReferenceConfig.class, ServiceConfig.class,
                ProtocolConfig.class, RegistryConfig.class, ConfigCenterConfig.class, MetadataReportConfig.class,
                ModuleConfig.class, SslConfig.class, MetricsConfig.class, MonitorConfig.class, MethodConfig.class);

        for (Class<? extends AbstractConfig> configClass : configClasses) {
            AbstractConfig config = configClass.newInstance();
            Map<String, String> metaData = config.getMetaData();
            Assertions.assertEquals(0, metaData.size(), "Expect empty metadata for new instance but found: "+metaData +" of "+configClass.getSimpleName());
            System.out.println(configClass.getSimpleName()+" metadata is checked.");
        }
    }
}
