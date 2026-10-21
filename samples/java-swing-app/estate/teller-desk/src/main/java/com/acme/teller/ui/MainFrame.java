package com.acme.teller.ui;

import java.awt.BorderLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;

public class MainFrame extends JFrame {
    private final AccountPanel accountPanel = new AccountPanel();

    public MainFrame() {
        super("Teller desk");
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem customers = new JMenuItem("Find customer...");
        customers.addActionListener(e -> new CustomerDialog(this).setVisible(true));
        file.add(customers);
        bar.add(file);
        setJMenuBar(bar);
        add(accountPanel, BorderLayout.CENTER);
        add(new JButton("Refresh"), BorderLayout.SOUTH);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
    }
}
