"""java analyzer (tree-sitter-java)."""

from __future__ import annotations

from legacy_audit.analyzers.java import JavaAnalyzer
from tests.analyzers.helpers import entities, run

DAO = """package com.acme.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.servlet.http.HttpServlet;

public class OrderDao {
    private static final String BY_ID = "SELECT id, total FROM orders WHERE id = ?";

    public void load(Connection c, String sort) throws Exception {
        PreparedStatement ps = c.prepareStatement(BY_ID);
        String sql = "SELECT * FROM orders o, customers c "
                   + "WHERE o.cust_id = c.id(+) ORDER BY " + sort;
        c.createStatement().executeQuery(sql);
        c.prepareCall("{call pkg_orders.close_day(?)}");
        java.net.Socket s = new java.net.Socket("legacy-host", 7001);
        String label = "Select a customer from the list";
    }

    public static void main(String[] args) { }
}
"""


def test_jdbc_sql_constants_concatenation_and_calls() -> None:
    facts = run(JavaAnalyzer(), "src/com/acme/dao/OrderDao.java", DAO)
    sql = entities(facts, "sql_statements")
    by_line = {s.line: s for s in sql}
    assert by_line[8].tables == ["ORDERS"] and by_line[8].source == "jdbc"
    dyn = by_line[12]
    assert (
        dyn.dynamic
        and dyn.tables == ["CUSTOMERS", "ORDERS"]
        and "OUTER JOIN (+)" in dyn.vendor_features
    )
    call = next(s for s in sql if s.type == "CALL")
    assert call.procedures == ["PKG_ORDERS.CLOSE_DAY"]
    assert not any("customer from the list" in s.text for s in sql)  # UI text is not SQL


def test_entry_points_sockets_and_upgrade_signal() -> None:
    facts = run(JavaAnalyzer(), "src/com/acme/dao/OrderDao.java", DAO)
    assert [(e.kind, e.name) for e in entities(facts, "entry_points")] == [
        ("main", "com.acme.dao.OrderDao")
    ]
    assert ("SOCKET", "legacy-host:7001") in {
        (i.type, i.target) for i in entities(facts, "integrations")
    }
    assert any(t.category == "upgrade-signal" for t in entities(facts, "tech_stack"))
    assert any(s.kind == "java-class" and s.name == "com.acme.dao.OrderDao" for s in facts.symbols)


CONTROLLER = """package com.acme.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.jms.core.JmsTemplate;

@Controller
@RequestMapping("/orders")
public class OrderController {
    private JmsTemplate jmsTemplate;

    @GetMapping("/{id}")
    public String show(@PathVariable long id) { return "orders/show"; }

    @PostMapping
    public String create() {
        jmsTemplate.convertAndSend("ORDERS.NEW", "x");
        return "redirect:/orders";
    }
}
"""


def test_spring_mvc_handlers_views_and_jms() -> None:
    facts = run(JavaAnalyzer(), "src/com/acme/web/OrderController.java", CONTROLLER)
    services = {(s.kind, s.name, s.url) for s in entities(facts, "services")}
    assert services == {
        ("spring-handler", "GET /orders/{id}", "/orders/{id}"),
        ("spring-handler", "POST /orders", "/orders"),
    }
    renders = [(r.kind, r.target) for r in facts.refs if r.kind == "renders"]
    assert renders == [("renders", "orders/show")]  # redirects are not views
    (queue,) = [i for i in entities(facts, "integrations") if i.type == "MQ"]
    assert queue.target == "ORDERS.NEW" and queue.producers


REPO = """package com.acme.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    List<Customer> findBySegmentAndStatus(String segment, String status);

    @Query("select c from Customer c where c.name like :name")
    List<Customer> search(String name);

    @Query(value = "SELECT * FROM customers WHERE ROWNUM < 10", nativeQuery = true)
    List<Customer> firstTen();
}
"""


def test_spring_data_queries() -> None:
    facts = run(JavaAnalyzer(), "src/com/acme/repo/CustomerRepository.java", REPO)
    kinds = sorted((s.source, s.type) for s in entities(facts, "sql_statements"))
    assert kinds == [("jpql", "SELECT"), ("native", "SELECT"), ("spring-data", "SELECT")]


def test_syntax_errors_are_coverage_gaps() -> None:
    facts = run(JavaAnalyzer(), "src/Bad.java", "public class Bad {\n void x( {\n}\n")
    assert facts.coverage.unparsed


SWING = """package com.acme.teller.ui;

import javax.swing.*;

public class CustomerDialog extends JDialog {
    private final JTextField name = new JTextField(20);
    private final JComboBox<String> branch = new JComboBox<>();
    private final JCheckBox active = new JCheckBox("Active");

    public CustomerDialog(JFrame owner) {
        super(owner, "Find customer", true);
        add(new AccountPanel());
        new HistoryFrame().setVisible(true);
    }
}

class AccountPanel extends JPanel {
    private final JTable accounts = new JTable();
}
"""

TELLER_APP = """package com.acme.teller;

import javax.swing.SwingUtilities;

public class TellerApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}
"""

JAVAFX = """package com.acme.teller.ui;

import javafx.application.Application;
import javafx.stage.Stage;

public class RatesView extends Application {
    @Override
    public void start(Stage stage) { }
}
"""


def test_swing_windows_panels_and_fields() -> None:
    facts = run(JavaAnalyzer(), "src/com/acme/teller/ui/CustomerDialog.java", SWING)
    screens = {
        s.name: (s.technology, s.partial, s.fields_count) for s in entities(facts, "screens")
    }
    assert screens == {"CustomerDialog": ("swing", False, 3), "AccountPanel": ("swing", True, 1)}
    forwards = {r.target for r in facts.refs if r.kind == "forwards"}
    assert {"AccountPanel", "HistoryFrame"} <= forwards


def test_desktop_main_and_javafx_application() -> None:
    facts = run(JavaAnalyzer(), "src/com/acme/teller/TellerApp.java", TELLER_APP)
    assert [e.kind for e in entities(facts, "entry_points")] == ["desktop-app"]
    facts = run(JavaAnalyzer(), "src/com/acme/teller/ui/RatesView.java", JAVAFX)
    assert [e.kind for e in entities(facts, "entry_points")] == ["desktop-app"]
    assert "JavaFX" in {t.name for t in entities(facts, "tech_stack")}
