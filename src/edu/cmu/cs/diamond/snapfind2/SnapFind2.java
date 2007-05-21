package edu.cmu.cs.diamond.snapfind2;

import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.KeyEvent.VK_A;
import static java.awt.event.KeyEvent.VK_C;
import static java.awt.event.KeyEvent.VK_D;
import static java.awt.event.KeyEvent.VK_E;
import static java.awt.event.KeyEvent.VK_H;
import static java.awt.event.KeyEvent.VK_I;
import static java.awt.event.KeyEvent.VK_L;
import static java.awt.event.KeyEvent.VK_N;
import static java.awt.event.KeyEvent.VK_O;
import static java.awt.event.KeyEvent.VK_P;
import static java.awt.event.KeyEvent.VK_Q;
import static java.awt.event.KeyEvent.VK_S;

import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import edu.cmu.cs.diamond.opendiamond.*;

public class SnapFind2 extends JFrame {

    final private List<Scope> scopes = ScopeSource.getPredefinedScopeList();

    final private SearchList searchList = new SearchList();

    final protected JButton startButton;

    final protected JButton stopButton;

    final protected Search search = Search.getSearch();

    final protected ThumbnailBox results = new ThumbnailBox();

    private JMenu scopeMenu;

    public SnapFind2() {
        super("SnapFind 2");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        setupMenu();

        // buttons
        startButton = new JButton("Start");
        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);

        startButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                results.clearAll();
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                startSearch();

                // start consumer
                new Thread(results).start();
                
                // start producer
                new Thread() {
                    @Override
                    public void run() {
                        BlockingQueue<Result> q = results.getQueue();
                        while (true) {
                            Result r = search.getNextResult();
                            while (true) {
                                try {
                                    q.put(r);
                                    System.out.println("snapfind put result");
                                    break;
                                } catch (InterruptedException e) {
                                }
                            }
                            if (r == null) {
                                break;
                            }
                        }
                        startButton.setEnabled(true);
                        stopButton.setEnabled(false);
                    }
                }.start();
            }
        });

        stopButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                search.stopSearch();
            }
        });

        setupWindow();

        pack();
    }

    protected void startSearch() {
        // read all enabled searches

        // set up search
        FilterCode fc;
        try {
            fc = new FilterCode(new FileInputStream(
                    "/opt/snapfind/lib/fil_rgb.a"));
            Filter f = new Filter("rgb", fc, "f_eval_img2rgb",
                    "f_init_img2rgb", "f_fini_img2rgb", 1, new String[0],
                    new String[0], 400);
            Searchlet s = new Searchlet();
            s.addFilter(f);
            s.setApplicationDependencies(new String[] { "rgb" });
            search.setSearchlet(s);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        search.startSearch();
    }

    private void setupWindow() {
        Box b = Box.createHorizontalBox();
        // b.setPreferredSize(new Dimension(850, 540));
        add(b);

        // left side
        Box c1 = Box.createVerticalBox();
        c1.add(searchList);

        Dimension minSize = new Dimension(100, 5);
        Dimension prefSize = new Dimension(250, 5);
        Dimension maxSize = new Dimension(250, 5);

        JComponent filler = new Box.Filler(minSize, prefSize, maxSize);
        // filler.setBorder(BorderFactory.createEtchedBorder());
        c1.add(filler);

        Box r1 = Box.createHorizontalBox();
        r1.add(startButton);
        r1.add(Box.createHorizontalStrut(20));
        stopButton.setEnabled(false);
        r1.add(stopButton);
        c1.add(r1);

        b.add(c1);
        // b.add(new JSeparator(SwingConstants.VERTICAL));
        // b.add(Box.createHorizontalGlue());

        // right side
        Box c2 = Box.createVerticalBox();
        c2.add(results);
        c2.add(results.getButton());
        b.add(c2);
    }

    private void setupMenu() {
        JMenuBar jmb = new JMenuBar();

        JMenu menu;
        JMenuItem mi;

        // Search
        menu = new JMenu("Search");
        menu.setMnemonic(VK_S);

        JMenu itemNew = new JMenu("New");
        itemNew.setMnemonic(VK_N);
        menu.add(itemNew);
        populateFiltersMenu(itemNew);

        menu.add(createMenuItem("New From Example...", VK_E,
                new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        newSearchFromExample();
                    }
                }));
        menu.addSeparator();

        menu.add(createMenuItem("Open...", VK_O, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                openSearch();
            }
        }));
        menu.add(createMenuItem("Import...", VK_I, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                importSearch();
            }
        }));
        menu.add(createMenuItem("Save As...", VK_A, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                saveAsSearch();
            }
        }));
        menu.addSeparator();
        mi = createMenuItem("Quit", VK_Q, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        });
        menu.add(mi);

        jmb.add(menu);

        // Scope
        scopeMenu = new JMenu("Scope");
        scopeMenu.setMnemonic(VK_C);
        populateScopeMenu(scopeMenu);
        jmb.add(scopeMenu);

        // Debug
        menu = new JMenu("Debug");
        menu.setMnemonic(VK_D);
        mi = createMenuItem("Stats Window", VK_S, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showStatsWindow();
            }
        });
        mi.setAccelerator(KeyStroke.getKeyStroke(VK_I, CTRL_DOWN_MASK));
        menu.add(mi);

        mi = createMenuItem("Progress Window", VK_P, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showProgressWindow();
            }
        });
        mi.setAccelerator(KeyStroke.getKeyStroke(VK_P, CTRL_DOWN_MASK));
        menu.add(mi);

        mi = createMenuItem("Log Window", VK_L, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showLogWindow();
            }
        });
        mi.setAccelerator(KeyStroke.getKeyStroke(VK_L, CTRL_DOWN_MASK));
        menu.add(mi);

        mi = createMenuItem("Cache Window", VK_C, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showCacheWindow();
            }
        });
        mi.setAccelerator(KeyStroke.getKeyStroke(VK_H, CTRL_DOWN_MASK));
        menu.add(mi);

        jmb.add(menu);

        setJMenuBar(jmb);
    }

    protected void showCacheWindow() {
        // TODO Auto-generated method stub

    }

    protected void showProgressWindow() {
        // TODO Auto-generated method stub

    }

    protected void showLogWindow() {
        // TODO Auto-generated method stub

    }

    protected void showStatsWindow() {
        // TODO Auto-generated method stub

    }

    private void populateScopeMenu(JMenu menu) {
        JRadioButtonMenuItem first = null;
        ButtonGroup bg = new ButtonGroup();

        for (final Scope s : scopes) {
            final JRadioButtonMenuItem mi = new JRadioButtonMenuItem(s
                    .getName());
            bg.add(mi);
            mi.addChangeListener(new ChangeListener() {
                public void stateChanged(ChangeEvent e) {
                    if (mi.isSelected()) {
                        System.out.println("Setting scope to " + s);
                        search.setScope(s);
                    }
                }
            });

            if (first == null) {
                first = mi;
            }
            menu.add(mi);
        }
        first.setSelected(true);
    }

    protected void newSearchFromExample() {
        searchList.addFilter(null, "filter");
    }

    protected void saveAsSearch() {
        // TODO Auto-generated method stub

    }

    protected void importSearch() {
        // TODO Auto-generated method stub

    }

    private JMenuItem createMenuItem(String title, int mnemonic,
            ActionListener a) {
        JMenuItem mi;
        mi = new JMenuItem(title, mnemonic);
        mi.addActionListener(a);
        return mi;
    }

    protected void openSearch() {
        // TODO Auto-generated method stub

    }

    private void populateFiltersMenu(JMenu itemNew) {
        // TODO Auto-generated method stub

    }

    public static void main(String[] args) {
        SnapFind2 sf = new SnapFind2();
        sf.setLocationByPlatform(true);
        sf.setVisible(true);
    }
}
