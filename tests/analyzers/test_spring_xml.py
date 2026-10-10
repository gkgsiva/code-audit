"""spring analyzer: bean XML, MVC, JMS/MQ, Batch, MyBatis, Hibernate mappings, security."""

from __future__ import annotations

from legacy_audit.analyzers.spring import SpringAnalyzer
from tests.analyzers.helpers import entities, run

BEANS = """<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:batch="http://www.springframework.org/schema/batch">
  <bean id="viewResolver" class="org.springframework.web.servlet.view.InternalResourceViewResolver">
    <property name="prefix" value="/WEB-INF/views/"/>
    <property name="suffix" value=".jsp"/>
  </bean>
  <bean id="cf" class="com.ibm.mq.jms.MQQueueConnectionFactory">
    <property name="queueManager" value="QM1"/>
    <property name="hostName" value="mqhost"/>
    <property name="port" value="1414"/>
  </bean>
  <bean id="tpl" class="org.springframework.jms.core.JmsTemplate">
    <property name="defaultDestinationName" value="OUT.Q"/>
  </bean>
  <bean id="container" class="org.springframework.jms.listener.DefaultMessageListenerContainer">
    <property name="destinationName" value="IN.Q"/>
  </bean>
  <bean id="reader" class="org.springframework.batch.item.database.JdbcCursorItemReader">
    <property name="sql" value="SELECT id FROM usage_records WHERE billed = 'N'"/>
  </bean>
  <batch:job id="nightly">
    <batch:step id="s1"><batch:tasklet ref="t1"/></batch:step>
    <batch:step id="s2"><batch:tasklet ref="t2"/></batch:step>
  </batch:job>
</beans>
"""


def test_beans_mq_batch_sql_and_view_resolver() -> None:
    facts = run(SpringAnalyzer(), "WEB-INF/app-context.xml", BEANS)
    ints = {
        (i.type, i.target, bool(i.producers), bool(i.consumers))
        for i in entities(facts, "integrations")
    }
    assert ("MQ-QMGR", "QM1@mqhost:1414", False, False) in ints
    assert ("MQ", "OUT.Q", True, False) in ints
    assert ("MQ", "IN.Q", False, True) in ints
    (job,) = entities(facts, "batch_jobs")
    assert (job.name, job.scheduler, job.steps) == ("nightly", "spring-batch", ["s1", "s2"])
    (sql,) = entities(facts, "sql_statements")
    assert sql.source == "spring-xml" and sql.tables == ["USAGE_RECORDS"]
    (resolver,) = [s for s in facts.symbols if s.kind == "view-resolver"]
    assert resolver.data["prefix"] == "/WEB-INF/views/" and resolver.data["suffix"] == ".jsp"


MAPPER = """<mapper namespace="com.acme.PaymentMapper">
  <select id="find">SELECT * FROM payments WHERE 1=1
    <if test="x != null">AND id = #{x}</if></select>
  <update id="settle">UPDATE payments SET settled = 'Y' WHERE id = #{id}</update>
</mapper>"""


def test_mybatis_mapper() -> None:
    facts = run(SpringAnalyzer(), "resources/PaymentMapper.xml", MAPPER)
    sql = sorted((s.type, s.dynamic, s.source) for s in entities(facts, "sql_statements"))
    assert sql == [("SELECT", True, "mybatis"), ("UPDATE", False, "mybatis")]


SECURITY = """<beans:beans xmlns="http://www.springframework.org/schema/security"
    xmlns:beans="http://www.springframework.org/schema/beans">
  <http><form-login login-page="/login"/></http>
  <ldap-server url="ldap://ldap.acme.local:389/dc=acme,dc=com"/>
</beans:beans>"""


def test_security_ldap() -> None:
    facts = run(SpringAnalyzer(), "WEB-INF/security.xml", SECURITY)
    assert ("LDAP", "ldap://ldap.acme.local:389") in {
        (i.type, i.target) for i in entities(facts, "integrations")
    }
    assert any(t.category == "auth" for t in entities(facts, "tech_stack"))
