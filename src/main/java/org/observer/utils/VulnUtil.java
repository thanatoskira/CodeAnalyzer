package org.observer.utils;

import java.io.File;

public class VulnUtil {
    private String saveDir = null;
    private String saveFile = "default.txt";

    public VulnUtil() {
    }

    public VulnUtil(String saveDir) {
        this.saveDir = saveDir;
    }

    public VulnUtil(String saveDir, String savFile) {
        this.saveDir = saveDir;
        this.saveFile = savFile;
    }

    public void all() {
        ssrfScanner();
        templateScanner();
        jndiScanner();
        ognlScanner();
        deserializeScanner();
        fastjsonScanner();
        expressionScanner();
        commandInjectionScanner();
        fileSecScanner();
        zipSlipScanner();
        xxeScanner();
    }

    public void ssrfScanner() {
        this.saveFile = "ssrf.txt";
        System.out.println("[+] Start SSRFScanner...");
        scan("java.net.URL#openConnection#()Ljava/net/URLConnection;#1");
    }

    public void templateScanner() {
        this.saveFile = "tpl.txt";
        System.out.println("[+] Start TemplateScanner...");
        scan("javax.validation.ConstraintValidatorContext#buildConstraintViolationWithTemplate#(Ljava/lang/String;)Ljavax/validation/ConstraintValidatorContext/ConstraintViolationBuilder;#1");
        scan("org.hibernate.validator.internal.engine.constraintvalidation.ConstraintValidatorContextImpl#buildConstraintViolationWithTemplate#(Ljava/lang/String;)Lorg/hibernate/validator/constraintvalidation/HibernateConstraintViolationBuilder;#1");
    }

    public void jndiScanner() {
        this.saveFile = "jndi.txt";
        System.out.println("[+] Start JndiScanner...");
        scan("javax.naming.Context#lookup#(Ljava/lang/String;)Ljava.lang.Object;#1");
        scan("javax.naming.Context#bind#(Ljava/lang/String;Ljava/lang/Object;)V#1");
        scan("javax.naming.Context#rebind#(Ljava/lang/String;Ljava/lang/Object;)V#1");
        scan("javax.naming.Context#unbind#(Ljava/lang/String;)V#1");
    }

    public void ognlScanner() {
        this.saveFile = "ognl.txt";
        System.out.println("[+] Start OgnlScanner...");
        scan("com.opensymphony.xwork.util.TextParseUtil#translateVariables#(Ljava/lang/String;Lcom/opensymphony/xwork/util/OgnlValueStack;)Ljava/lang/String;#1");
        scan("org.springframework.expression.Expression#getValue#null#1");
    }

    public void deserializeScanner() {
        this.saveFile = "deserialization.txt";
        System.out.println("[+] Start DeserializeScanner...");
        scan("java.io.ObjectInput#readObject#null#1");
        scan("java.io.Externalizable#readExternal#null#1");
        scan("com.thoughtworks.xstream#fromXML#null#1");
        scan("org.yaml.snakeyaml.Yaml#load#null#1");
    }

    public void fastjsonScanner() {
        this.saveFile = "fastjson.txt";
        System.out.println("[+] Start FastJsonScanner...");
        scan("com.alibaba.fastjson.JSON#parse#null#1");
        scan("com.alibaba.fastjson.JSON#parseObject#null#1");
        scan("com.alibaba.fastjson.JSON#parseArray#null#1");
    }

    public void expressionScanner() {
        this.saveFile = "expression.txt";
        System.out.println("[+] Start ExpressionScanner...");
        scan("javax.script.ScriptEngine#eval#null#1");
        scan("groovy.lang.GroovyShell#evaluate#null#1");
        // spring-cloud-gateway
        scan("org.springframework.expression.Expression#getValue#null#1");
    }

    public void commandInjectionScanner() {
        this.saveFile = "cmdinject.txt";
        System.out.println("[+] Start CommandInjectionScanner...");
        scan("java.lang.ProcessBuilder#start#()Ljava/lang/Process;#1");
        // 虽然 ProcessBuilder#start 中会包含 java.lang.Runtime#exec，但前提时会扫描 rt.jar
        scan("java.lang.Runtime#exec#null#1");
    }

    public void fileSecScanner() {
        this.saveFile = "filesec.txt";
        System.out.println("[+] Start FileSecScanner...");
        scan("java.io.File#renameTo#(Ljava/io/File;)V#1");
        scan("java.io.FileOutputStream#write#null#1");
        scan("java.io.FileInputStream#read#null#1");
    }

    public void zipSlipScanner() {
        this.saveFile = "zipslip.txt";
        System.out.println("[+] Start ZipSlipScanner...");
        scan("java.util.zip.ZipEntry#init#null#1");
    }

    public void xxeScanner() {
        this.saveFile = "xxe.txt";
        System.out.println("[+] Start XXEScanner...");
        scan("org.jdom2.input.SAXBuilder#build#null#1");
        scan("javax.xml.parsers.SAXParser#parse#null#1");
        scan("javax.xml.transform.sax.SAXTransformerFactory#newTransformerHandler#null#1");
        scan("javax.xml.validation.SchemaFactory#newSchema#null#1");
        scan("javax.xml.transform.Transformer#transform#null#1");
        scan("javax.xml.validation.Validator#validate#null#1");
        scan("org.xml.sax.XMLReader#parse#null#1");
        scan("javax.xml.parsers.DocumentBuilder#parse#null#1");
    }

    public void scan(String call) {
        scan(call, true);
    }

    public void scan(String call, boolean bt) {
        if (saveDir == null) {
            if (bt) {
                PrettyPrintUtil.prettyPrint(SearchUtil.getBTUpgradeCaller(call));
            } else {
                PrettyPrintUtil.prettyPrint(SearchUtil.getBTCaller(call));
            }
        } else {
            File dir = new File(saveDir);
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    throw new RuntimeException("mkdir falied: " + dir);
                }
            }
            if (bt) {
                PrettyPrintUtil.saveToFile(SearchUtil.getBTUpgradeCaller(call), String.format("%s/%s", saveDir, saveFile));
            } else {
                PrettyPrintUtil.saveToFile(SearchUtil.getBTCaller(call), String.format("%s/%s", saveDir, saveFile));
            }
        }
    }
}