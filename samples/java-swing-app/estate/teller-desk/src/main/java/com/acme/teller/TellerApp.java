package com.acme.teller;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.acme.teller.ui.MainFrame;

public class TellerApp {
    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}
