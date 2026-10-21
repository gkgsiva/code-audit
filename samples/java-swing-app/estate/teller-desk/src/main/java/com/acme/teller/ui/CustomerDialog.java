package com.acme.teller.ui;

import java.awt.GridLayout;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;

import com.acme.teller.dao.CustomerDao;

public class CustomerDialog extends JDialog {
    private final JTextField nameField = new JTextField(20);
    private final JTextField accountField = new JTextField(12);
    private final JComboBox<String> branchBox = new JComboBox<>(new String[] {"ALL", "NORTH", "SOUTH"});
    private final JTable results = new JTable();
    private final CustomerDao dao = new CustomerDao();

    public CustomerDialog(JFrame owner) {
        super(owner, "Find customer", true);
        setLayout(new GridLayout(4, 2));
        add(new JLabel("Name"));
        add(nameField);
        add(new JLabel("Account"));
        add(accountField);
        add(branchBox);
        JButton search = new JButton("Search");
        search.addActionListener(e -> search());
        add(search);
        add(results);
        pack();
    }

    private void search() {
        List<String[]> rows = dao.find(nameField.getText(), (String) branchBox.getSelectedItem());
        results.setModel(new javax.swing.table.DefaultTableModel(rows.toArray(new Object[0][]), new String[] {"Id", "Name"}));
    }
}
