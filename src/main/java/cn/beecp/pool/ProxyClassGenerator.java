/*
 * Copyright(C) Chris2018998
 * Contact:Chris2018998@tom.com
 *
 * Licensed under GNU General Public License version 3.0.
 */
package cn.beecp.pool;

import javassist.*;

import java.sql.*;
import java.util.HashSet;
import java.util.LinkedList;

/**
 * An independent execution toolkit class to generate JDBC proxy classes with javassist,
 * then write to class folder.
 *
 * @author Chris.Liao
 * @version 1.0
 */
@SuppressWarnings("unchecked")
final class ProxyClassGenerator {

    /**
     * default classes output folder in project
     */
    private static String folder = "BeeCP/target/classes";

    /**
     * @param args take the first argument as classes generated output folder,otherwise take default folder
     * @throws Exception throw exception in generating process
     */
    public static void main(String[] args) throws Exception {
        if (args != null && args.length > 0)
            folder = args[0];

        writeProxyFile(folder);
    }

    /**
     * write to disk folder
     *
     * @param folder classes generated will write to it
     * @throws Exception if failed to write file to disk
     */
    public static void writeProxyFile(String folder) throws Exception {
        ProxyClassGenerator builder = new ProxyClassGenerator();
        CtClass[] ctClasses = builder.createJdbcProxyClasses();
        for (CtClass ctClass : ctClasses) {
            ctClass.writeFile(folder);
        }
    }

    /**
     * create all wrapper classes based on JDBC some interfaces
     *
     * @return a class array generated by javassist
     * <p>
     * new Class:
     * cn.beecp.pool.ProxyConnection
     * cn.beecp.pool.ProxyStatement
     * cn.beecp.pool.ProxyPsStatement
     * cn.beecp.pool.ProxyCsStatement
     * cn.beecp.pool.ProxyResultSet
     * @throws Exception if failed to generate class
     */
    private CtClass[] createJdbcProxyClasses() throws Exception {
        try {
            ClassPool classPool = ClassPool.getDefault();
            classPool.importPackage("java.sql");
            classPool.importPackage("cn.beecp.pool");
            classPool.appendClassPath(new LoaderClassPath(this.getClass().getClassLoader()));

            //............Connection Begin.........
            CtClass ctConnectionClass = classPool.get(Connection.class.getName());
            CtClass ctProxyConnectionBaseClass = classPool.get(ProxyConnectionBase.class.getName());
            String ctProxyConnectionClassName = "cn.beecp.pool.ProxyConnection";
            CtClass ctProxyConnectionClass = classPool.makeClass(ctProxyConnectionClassName, ctProxyConnectionBaseClass);
            ctProxyConnectionClass.setModifiers(Modifier.PUBLIC | Modifier.FINAL);

            CtClass[] conCreateParam = new CtClass[]{
                    classPool.get("cn.beecp.pool.PooledConnection")};
            CtConstructor ctConstructor = new CtConstructor(conCreateParam, ctProxyConnectionClass);
            ctConstructor.setModifiers(Modifier.PUBLIC);
            ctConstructor.setBody("{super($$);}");
            ctProxyConnectionClass.addConstructor(ctConstructor);
            //...............Connection End................

            //.............statement Begin.............
            CtClass ctStatementClass = classPool.get(Statement.class.getName());
            CtClass ctProxyStatementBaseClass = classPool.get(ProxyStatementBase.class.getName());
            String ctProxyStatementClassName = "cn.beecp.pool.ProxyStatement";
            CtClass ctProxyStatementClass = classPool.makeClass(ctProxyStatementClassName, ctProxyStatementBaseClass);
            ctProxyStatementClass.setModifiers(Modifier.PUBLIC);
            CtClass[] statementCreateParam = new CtClass[]{
                    classPool.get("java.sql.Statement"),
                    classPool.get("cn.beecp.pool.PooledConnection")
            };
            ctConstructor = new CtConstructor(statementCreateParam, ctProxyStatementClass);
            ctConstructor.setModifiers(Modifier.PUBLIC);
            ctConstructor.setBody("{super($$);}");
            ctProxyStatementClass.addConstructor(ctConstructor);
            //.............Statement Begin...............

            //............PreparedStatement Begin...............
            CtClass ctPreparedStatementClass = classPool.get(PreparedStatement.class.getName());
            String ctProxyPsStatementClassName = "cn.beecp.pool.ProxyPsStatement";
            CtClass ctProxyPsStatementClass = classPool.makeClass(ctProxyPsStatementClassName, ctProxyStatementClass);
            ctProxyPsStatementClass.setInterfaces(new CtClass[]{ctPreparedStatementClass});
            ctProxyPsStatementClass.setModifiers(Modifier.PUBLIC);

            CtClass[] statementPsCreateParam = new CtClass[]{
                    classPool.get("java.sql.PreparedStatement"),
                    classPool.get("cn.beecp.pool.PooledConnection")

            };
            ctConstructor = new CtConstructor(statementPsCreateParam, ctProxyPsStatementClass);
            ctConstructor.setModifiers(Modifier.PUBLIC);
            ctConstructor.setBody("{super($$);}");
            ctProxyPsStatementClass.addConstructor(ctConstructor);
            //........PreparedStatement End..............

            //..............CallableStatement Begin.............
            CtClass ctCallableStatementClass = classPool.get(CallableStatement.class.getName());
            String ctProxyCsStatementClassName = "cn.beecp.pool.ProxyCsStatement";
            CtClass ctProxyCsStatementClass = classPool.makeClass(ctProxyCsStatementClassName, ctProxyPsStatementClass);
            ctProxyCsStatementClass.setInterfaces(new CtClass[]{ctCallableStatementClass});
            ctProxyCsStatementClass.setModifiers(Modifier.PUBLIC);

            CtClass[] statementCsCreateParam = new CtClass[]{
                    classPool.get("java.sql.CallableStatement"),
                    classPool.get("cn.beecp.pool.PooledConnection")};
            ctConstructor = new CtConstructor(statementCsCreateParam, ctProxyCsStatementClass);
            ctConstructor.setModifiers(Modifier.PUBLIC);
            ctConstructor.setBody("{super($$);}");
            ctProxyCsStatementClass.addConstructor(ctConstructor);
            //...........CallableStatement End...............

            //..............DatabaseMetaData Begin.............
            CtClass ctDatabaseMetaDataClass = classPool.get(DatabaseMetaData.class.getName());
            CtClass ctProxyDatabaseMetaDataBaseClass = classPool.get(ProxyDatabaseMetaDataBase.class.getName());
            String ctProxyDatabaseMetaDataClassName = "cn.beecp.pool.ProxyDatabaseMetaData";
            CtClass ctProxyDatabaseMetaDataClass = classPool.makeClass(ctProxyDatabaseMetaDataClassName, ctProxyDatabaseMetaDataBaseClass);
            ctProxyDatabaseMetaDataClass.setModifiers(Modifier.PUBLIC | Modifier.FINAL);

            CtClass[] databaseMetaData = new CtClass[]{
                    classPool.get("java.sql.DatabaseMetaData"),
                    classPool.get("cn.beecp.pool.PooledConnection")};
            ctConstructor = new CtConstructor(databaseMetaData, ctProxyDatabaseMetaDataClass);
            ctConstructor.setModifiers(Modifier.PUBLIC);
            ctConstructor.setBody("{super($$);}");
            ctProxyDatabaseMetaDataClass.addConstructor(ctConstructor);
            //...........DatabaseMetaData End...............

            //............... Result Begin..................
            CtClass ctResultSetClass = classPool.get(ResultSet.class.getName());
            CtClass ctProxyResultSetBaseClass = classPool.get(ProxyResultSetBase.class.getName());
            String ctProxyResultSetClassName = "cn.beecp.pool.ProxyResultSet";
            CtClass ctProxyResultSetClass = classPool.makeClass(ctProxyResultSetClassName, ctProxyResultSetBaseClass);
            ctProxyResultSetClass.setModifiers(Modifier.PUBLIC | Modifier.FINAL);

            CtClass[] resultSetCreateParam1 = new CtClass[]{
                    classPool.get("java.sql.ResultSet"),
                    classPool.get("cn.beecp.pool.PooledConnection")};
            CtConstructor ctConstructor1 = new CtConstructor(resultSetCreateParam1, ctProxyResultSetClass);
            ctConstructor1.setModifiers(Modifier.PUBLIC);
            ctConstructor1.setBody("{super($$);}");
            ctProxyResultSetClass.addConstructor(ctConstructor1);

            CtClass[] resultSetCreateParam2 = new CtClass[]{
                    classPool.get("java.sql.ResultSet"),
                    classPool.get("cn.beecp.pool.ProxyStatementBase"),
                    classPool.get("cn.beecp.pool.PooledConnection")};
            ctConstructor = new CtConstructor(resultSetCreateParam2, ctProxyResultSetClass);
            ctConstructor.setModifiers(Modifier.PUBLIC);
            ctConstructor.setBody("{super($$);}");
            ctProxyResultSetClass.addConstructor(ctConstructor);
            //............Result End...............
            this.createProxyConnectionClass(classPool, ctProxyConnectionClass, ctConnectionClass, ctProxyConnectionBaseClass);
            this.createProxyStatementClass(classPool, ctProxyStatementClass, ctStatementClass, ctProxyStatementBaseClass);
            this.createProxyStatementClass(classPool, ctProxyPsStatementClass, ctPreparedStatementClass, ctProxyStatementClass);
            this.createProxyStatementClass(classPool, ctProxyCsStatementClass, ctCallableStatementClass, ctProxyPsStatementClass);
            this.createProxyDatabaseMetaDataClass(classPool, ctProxyDatabaseMetaDataClass, ctDatabaseMetaDataClass, ctProxyDatabaseMetaDataBaseClass);
            this.createProxyResultSetClass(classPool, ctProxyResultSetClass, ctResultSetClass, ctProxyResultSetBaseClass);

            //............... ProxyObjectFactory Begin..................
            CtClass ctProxyObjectFactoryClass = classPool.get(PoolStaticCenter.class.getName());
            CtMethod createProxyConnectionMethod = null;
            CtMethod createProxyResultSetMethod = null;

            CtMethod[] ctMethods = ctProxyObjectFactoryClass.getDeclaredMethods();
            for (CtMethod method : ctMethods) {
                if ("createProxyConnection".equals(method.getName())) {
                    createProxyConnectionMethod = method;
                } else if ("createProxyResultSet".equals(method.getName())) {
                    createProxyResultSetMethod = method;
                }
            }

            createProxyConnectionMethod.setBody("{$2.lastUsedCon=$1; return new ProxyConnection($1);}");
            createProxyResultSetMethod.setBody("{return new ProxyResultSet($$);}");
            //............... ProxyObjectFactory end..................

            return new CtClass[]{
                    ctProxyConnectionClass,
                    ctProxyStatementClass,
                    ctProxyPsStatementClass,
                    ctProxyCsStatementClass,
                    ctProxyDatabaseMetaDataClass,
                    ctProxyResultSetClass,
                    ctProxyObjectFactoryClass};
        } catch (Throwable e) {
            e.printStackTrace();
            throw new Exception(e);
        }
    }

    /**
     * create connection proxy class
     * *
     *
     * @param classPool                   javassist class pool
     * @param ctConnectionClassProxyClass connection implemented sub class will be generated
     * @param ctConnectionClass           connection interface in javassist class pool
     * @param ctConBaseClass              super class extend by 'ctctConnectionClassProxyClass'
     * @return proxy class base on connection interface
     * @throws Exception some error occurred
     */
    private Class createProxyConnectionClass(ClassPool classPool, CtClass ctConnectionClassProxyClass, CtClass ctConnectionClass, CtClass ctConBaseClass) throws Exception {
        CtMethod[] ctSuperClassMethods = ctConBaseClass.getMethods();
        HashSet notNeedAddProxyMethods = new HashSet(16);
        for (int i = 0, l = ctSuperClassMethods.length; i < l; i++) {
            int modifiers = ctSuperClassMethods[i].getModifiers();
            if ((!Modifier.isAbstract(modifiers) && (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)))
                    || Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers) || Modifier.isNative(modifiers)) {
                notNeedAddProxyMethods.add(ctSuperClassMethods[i].getName() + ctSuperClassMethods[i].getSignature());
            }
        }

        LinkedList<CtMethod> linkedList = new LinkedList<CtMethod>();
        resolveInterfaceMethods(ctConnectionClass, linkedList, notNeedAddProxyMethods);

        CtClass ctStatementClass = classPool.get(Statement.class.getName());
        CtClass ctPreparedStatementClass = classPool.get(PreparedStatement.class.getName());
        CtClass ctCallableStatementClass = classPool.get(CallableStatement.class.getName());
        CtClass ctDatabaseMetaDataIntf = classPool.get(DatabaseMetaData.class.getName());

        StringBuilder methodBuffer = new StringBuilder(50);
        for (CtMethod ctMethod : linkedList) {
            String methodName = ctMethod.getName();
            CtMethod newCtMethodm = CtNewMethod.copy(ctMethod, ctConnectionClassProxyClass, null);
            newCtMethodm.setModifiers(Modifier.PUBLIC);

            methodBuffer.delete(0, methodBuffer.length());
            methodBuffer.append("{");
            if (ctMethod.getReturnType() == ctStatementClass) {
                methodBuffer.append("return new ProxyStatement(delegate." + methodName + "($$),pCon);");
            } else if (ctMethod.getReturnType() == ctPreparedStatementClass) {
                methodBuffer.append("return new ProxyPsStatement(delegate." + methodName + "($$),pCon);");
            } else if (ctMethod.getReturnType() == ctCallableStatementClass) {
                methodBuffer.append("return new ProxyCsStatement(delegate." + methodName + "($$),pCon);");
            } else if (ctMethod.getReturnType() == ctDatabaseMetaDataIntf) {
                methodBuffer.append("return new ProxyDatabaseMetaData(delegate." + methodName + "($$),pCon);");
            } else if (methodName.equals("close")) {
                //methodBuffer.append("super."+methodName + "($$);");
            } else if (ctMethod.getReturnType() == CtClass.voidType) {
                methodBuffer.append("delegate." + methodName + "($$);");
            } else {
                methodBuffer.append("return delegate." + methodName + "($$);");
            }

            methodBuffer.append("}");
            newCtMethodm.setBody(methodBuffer.toString());
            ctConnectionClassProxyClass.addMethod(newCtMethodm);
        }
        return ctConnectionClassProxyClass.toClass();
    }

    private Class createProxyStatementClass(ClassPool classPool, CtClass statementProxyClass, CtClass ctStatementClass, CtClass ctStatementSuperClass) throws Exception {
        CtMethod[] ctSuperClassMethods = ctStatementSuperClass.getMethods();
        HashSet superClassSignatureSet = new HashSet(16);
        for (int i = 0, l = ctSuperClassMethods.length; i < l; i++) {
            int modifiers = ctSuperClassMethods[i].getModifiers();
            if ((!Modifier.isAbstract(modifiers) && (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)))
                    || Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers) || Modifier.isNative(modifiers)) {
                superClassSignatureSet.add(ctSuperClassMethods[i].getName() + ctSuperClassMethods[i].getSignature());
            }
        }

        LinkedList<CtMethod> linkedList = new LinkedList<CtMethod>();
        resolveInterfaceMethods(ctStatementClass, linkedList, superClassSignatureSet);

        CtClass ctResultSetClass = classPool.get(ResultSet.class.getName());
        StringBuilder methodBuffer = new StringBuilder(50);

        String delegateName = "delegate.";
        if ("java.sql.PreparedStatement".equals(ctStatementClass.getName())) {
            delegateName = "((PreparedStatement)delegate).";
        } else if ("java.sql.CallableStatement".equals(ctStatementClass.getName())) {
            delegateName = "((CallableStatement)delegate).";
        }

        for (CtMethod ctMethod : linkedList) {
            String methodName = ctMethod.getName();
            CtMethod newCtMethodm = CtNewMethod.copy(ctMethod, statementProxyClass, null);
            newCtMethodm.setModifiers(Modifier.PUBLIC);

            methodBuffer.delete(0, methodBuffer.length());
            methodBuffer.append("{");

            if (ctMethod.getReturnType() == CtClass.voidType) {
                methodBuffer.append(delegateName + methodName + "($$);");
                if (methodName.startsWith("execute"))
                    methodBuffer.append("pCon.updateAccessTime();");
            } else {
                if (methodName.startsWith("execute")) {
                    methodBuffer.append(ctMethod.getReturnType().getName() + " re=" + delegateName + methodName + "($$);")
                            .append("pCon.updateAccessTime();");
                    if (ctMethod.getReturnType() == ctResultSetClass) {
                        methodBuffer.append("return new ProxyResultSet(re,this,pCon);");
                    } else {
                        methodBuffer.append("return re;");
                    }
                } else {
                    if (ctMethod.getReturnType() == ctResultSetClass) {
                        methodBuffer.append("return new ProxyResultSet(delegate." + methodName + "($$),this,pCon);");
                    } else
                        methodBuffer.append("return " + delegateName + methodName + "($$);");
                }
            }
            methodBuffer.append("}");
            newCtMethodm.setBody(methodBuffer.toString());
            statementProxyClass.addMethod(newCtMethodm);
        }
        return statementProxyClass.toClass();
    }

    //ctProxyDatabaseMetaDataClass,ctDatabaseMetaDataIntf,ctDatabaseMetaDataSuperClass
    private Class createProxyDatabaseMetaDataClass(ClassPool classPool, CtClass ctProxyDatabaseMetaDataClass, CtClass ctDatabaseMetaDataIntf, CtClass ctDatabaseMetaDataSuperClass) throws Exception {
        CtMethod[] ctSuperClassMethods = ctDatabaseMetaDataSuperClass.getMethods();
        HashSet superClassSignatureSet = new HashSet(16);
        for (int i = 0, l = ctSuperClassMethods.length; i < l; i++) {
            int modifiers = ctSuperClassMethods[i].getModifiers();
            if ((!Modifier.isAbstract(modifiers) && (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)))
                    || Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers) || Modifier.isNative(modifiers)) {
                superClassSignatureSet.add(ctSuperClassMethods[i].getName() + ctSuperClassMethods[i].getSignature());
            }
        }

        LinkedList<CtMethod> linkedList = new LinkedList();
        resolveInterfaceMethods(ctDatabaseMetaDataIntf, linkedList, superClassSignatureSet);
        CtClass ctResultSetClass = classPool.get(ResultSet.class.getName());

        StringBuilder methodBuffer = new StringBuilder(40);
        for (CtMethod ctMethod : linkedList) {
            String methodName = ctMethod.getName();
            CtMethod newCtMethodm = CtNewMethod.copy(ctMethod, ctProxyDatabaseMetaDataClass, null);
            newCtMethodm.setModifiers(Modifier.PUBLIC);

            methodBuffer.delete(0, methodBuffer.length());
            methodBuffer.append("{")
                    .append("checkClosed();");
            if (ctMethod.getReturnType() == ctResultSetClass) {
                methodBuffer.append("return new ProxyResultSet(delegate." + methodName + "($$),pCon);");
            } else if (ctMethod.getReturnType() == CtClass.voidType) {
                methodBuffer.append("delegate." + methodName + "($$);");
            } else {
                methodBuffer.append("return delegate." + methodName + "($$);");
            }

            methodBuffer.append("}");
            newCtMethodm.setBody(methodBuffer.toString());
            ctProxyDatabaseMetaDataClass.addMethod(newCtMethodm);
        }
        return ctProxyDatabaseMetaDataClass.toClass();
    }

    private Class createProxyResultSetClass(ClassPool classPool, CtClass ctResultSetClassProxyClass, CtClass ctResultSetClass, CtClass ctResultSetClassSuperClass) throws Exception {
        CtMethod[] ctSuperClassMethods = ctResultSetClassSuperClass.getMethods();
        HashSet superClassSignatureSet = new HashSet(16);
        for (int i = 0, l = ctSuperClassMethods.length; i < l; i++) {
            int modifiers = ctSuperClassMethods[i].getModifiers();
            if ((!Modifier.isAbstract(modifiers) && (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)))
                    || Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers) || Modifier.isNative(modifiers)) {
                superClassSignatureSet.add(ctSuperClassMethods[i].getName() + ctSuperClassMethods[i].getSignature());
            }
        }

        LinkedList<CtMethod> linkedList = new LinkedList();
        resolveInterfaceMethods(ctResultSetClass, linkedList, superClassSignatureSet);
        StringBuilder methodBuffer = new StringBuilder(25);

        for (CtMethod ctMethod : linkedList) {
            String methodName = ctMethod.getName();
            CtMethod newCtMethodm = CtNewMethod.copy(ctMethod, ctResultSetClassProxyClass, null);
            newCtMethodm.setModifiers(Modifier.PUBLIC);

            methodBuffer.delete(0, methodBuffer.length());
            methodBuffer.append("{");

            if (methodName.equals("close")) {
                //methodBuffer.append("super." + methodName + "($$);");
            } else {
                if (ctMethod.getReturnType() == CtClass.voidType) {
                    methodBuffer.append("delegate." + methodName + "($$);");
                    if (methodName.startsWith("insertRow") || methodName.startsWith("updateRow") || methodName.startsWith("deleteRow"))
                        methodBuffer.append(" pCon.updateAccessTime();");
                } else {
                    if (methodName.startsWith("insertRow") || methodName.startsWith("updateRow") || methodName.startsWith("deleteRow")) {
                        methodBuffer.append(ctMethod.getReturnType().getName() + " re=delegate." + methodName + "($$);")
                                .append(" pCon.updateAccessTime();")
                                .append(" return re;");
                    } else {
                        methodBuffer.append("return delegate." + methodName + "($$);");
                    }
                }
            }

            methodBuffer.append("}");
            newCtMethodm.setBody(methodBuffer.toString());
            ctResultSetClassProxyClass.addMethod(newCtMethodm);
        }
        return ctResultSetClassProxyClass.toClass();
    }

    private void resolveInterfaceMethods(CtClass interfaceClass, LinkedList linkedList, HashSet exitSignatureSet) throws Exception {
        CtMethod[] ctMethods = interfaceClass.getDeclaredMethods();
        for (int i = 0; i < ctMethods.length; i++) {
            int modifiers = ctMethods[i].getModifiers();
            String signature = ctMethods[i].getName() + ctMethods[i].getSignature();
            if (Modifier.isAbstract(modifiers)
                    && (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))
                    && !Modifier.isStatic(modifiers)
                    && !Modifier.isFinal(modifiers)
                    && !exitSignatureSet.contains(signature)) {

                linkedList.add(ctMethods[i]);
                exitSignatureSet.add(signature);
            }
        }

        CtClass[] superInterfaces = interfaceClass.getInterfaces();
        for (int i = 0; i < superInterfaces.length; i++) {
            resolveInterfaceMethods(superInterfaces[i], linkedList, exitSignatureSet);
        }
    }
}


