package com.acme.teller.ui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

public class AccountPanel extends JPanel {
    private final JTextField balance = new JTextField(10);

    public AccountPanel() {
        add(new JLabel("Balance"));
        add(balance);
    }
}
