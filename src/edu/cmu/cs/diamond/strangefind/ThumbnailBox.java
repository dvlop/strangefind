/*
 *  StrangeFind, an anomaly detector for the OpenDiamond platform
 *
 *  Copyright (c) 2007-2008 Carnegie Mellon University
 *  All rights reserved.
 *
 *  StrangeFind is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, version 2.
 *
 *  StrangeFind is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with StrangeFind. If not, see <http://www.gnu.org/licenses/>.
 *
 *  Linking StrangeFind statically or dynamically with other modules is
 *  making a combined work based on StrangeFind. Thus, the terms and
 *  conditions of the GNU General Public License cover the whole
 *  combination.
 * 
 *  In addition, as a special exception, the copyright holders of
 *  StrangeFind give you permission to combine StrangeFind with free software
 *  programs or libraries that are released under the GNU LGPL or the
 *  Eclipse Public License 1.0. You may copy and distribute such a system
 *  following the terms of the GNU GPL for StrangeFind and the licenses of
 *  the other code concerned, provided that you include the source code of
 *  that other code when and as the GNU GPL requires distribution of source
 *  code.
 *
 *  Note that people who make modified versions of StrangeFind are not
 *  obligated to grant this special exception for their modified versions;
 *  it is their choice whether to do so. The GNU General Public License
 *  gives permission to release a modified version without this exception;
 *  this exception also makes it possible to release a modified version
 *  which carries forward this exception.
 */

package edu.cmu.cs.diamond.strangefind;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.TimerTask;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;

import edu.cmu.cs.diamond.opendiamond.*;

public class ThumbnailBox extends JPanel {
    volatile protected int nextEmpty = 0;

    final static private int ROWS = 3;

    final static private int COLS = 3;

    final protected ResultViewer[] pics = new ResultViewer[ROWS * COLS];

    final protected JButton nextButton = new JButton("Next");

    volatile protected Thread resultGatherer;

    volatile protected boolean running;

    protected Search search;

    protected SearchFactory factory;

    final protected Object fullSynchronizer = new Object();

    private Annotator annotator;

    protected Decorator decorator;

    final protected StatisticsBar stats = new StatisticsBar();

    final protected Map<String, Double> globalSessionVariables;

    final protected AbstractTableModel sessionVariablesTableModel;

    final protected Timer statsTimer = new Timer(500, new ActionListener() {
        public void actionPerformed(ActionEvent e) {
            // because it is Swing Timer, this is called from the
            // AWT dispatch thread
            Map<String, ServerStatistics> serverStats = null;
            try {
                serverStats = search.getStatistics();
            } catch (SearchClosedException e1) {
                return;
            } catch (IOException e1) {
                e1.printStackTrace();
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
            boolean hasStats = false;
            for (ServerStatistics s : serverStats.values()) {
                if (s.getTotalObjects() != 0) {
                    hasStats = true;
                    break;
                }
            }
            if (hasStats) {
                stats.update(serverStats);
            } else {
                stats.setIndeterminateMessage("Waiting for First Results");
            }
        }
    });

    final protected java.util.Timer sessionVarsTimer = new java.util.Timer(true);

    protected long sessionVarsInterval = 1000 * StrangeFind.INITIAL_SESSION_VARIABLES_UPDATE_INTERVAL;

    TimerTask createSessionVarsTimerTask() {
        System.out.println("creating timer task");
        TimerTask sessionVarsTimerTask = new TimerTask() {
            @Override
            public void run() {
                // System.out.println("************timer task running");
                try {
                    search.mergeSessionVariables(globalSessionVariables);
                } catch (SearchClosedException e) {
                    // ignore
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                sessionVariablesTableModel.fireTableDataChanged();
            }
        };

        return sessionVarsTimerTask;
    }

    private volatile boolean searchRunning;

    private volatile boolean updateSessionVars = true;

    private TimerTask sessionVarsTimerTask;

    private final JButton stopButton;

    private final JButton startButton;

    public ThumbnailBox(Map<String, Double> globalSessionVariables,
            AbstractTableModel sessionVariablesTableModel, JButton stopButton,
            JButton startButton) {
        super();

        this.globalSessionVariables = globalSessionVariables;

        this.sessionVariablesTableModel = sessionVariablesTableModel;

        this.stopButton = stopButton;

        this.startButton = startButton;

        Box v = Box.createVerticalBox();
        add(v);
        // v.setBorder(BorderFactory.createEtchedBorder(Color.RED, Color.BLUE));
        Box h = null;
        for (int i = 0; i < pics.length; i++) {
            boolean addBox = false;
            if (i % COLS == 0) {
                h = Box.createHorizontalBox();
                addBox = true;
            }

            ResultViewer b = new ResultViewer();

            h.add(b);
            pics[i] = b;

            if (addBox) {
                v.add(h);
            }
        }

        h = Box.createHorizontalBox();
        h.setAlignmentX(Component.CENTER_ALIGNMENT);

        h.add(stats);
        h.add(Box.createHorizontalStrut(10));

        h.add(nextButton);
        nextButton.setEnabled(false);
        nextButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                // next is clicked
                nextButton.setEnabled(false);
                synchronized (fullSynchronizer) {
                    clearAll();
                    fullSynchronizer.notify();
                }
            }
        });

        v.add(h);
    }

    public void setAnnotator(Annotator a) {
        annotator = a;
    }

    public void setDecorator(Decorator d) {
        decorator = d;
    }

    protected boolean isFull() {
        return nextEmpty >= pics.length;
    }

    protected void clearAll() {
        nextEmpty = 0;
        for (ResultViewer r : pics) {
            r.setResult(null, null, null);
            r.commitResult();
        }
    }

    protected void fillNext(final Result r) {
        System.out.println("fillNext " + r);
        if (!running) {
            return;
        }

        // update
        final ResultViewer v = pics[nextEmpty++];

        // loading message
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                v.setText("Loading");
            }
        });

        final String annotation;
        final String nonHTMLAnnotation;
        final String tooltipAnnotation;
        final String oneLineAnnotation;
        final String verboseAnnotation;
        if (annotator != null) {
            annotation = annotator.annotate(r);
            nonHTMLAnnotation = annotator.annotateNonHTML(r);
            tooltipAnnotation = annotator.annotateTooltip(r);
            oneLineAnnotation = annotator.annotateOneLine(r);
            verboseAnnotation = annotator.annotateVerbose(r);
        } else {
            annotation = null;
            nonHTMLAnnotation = null;
            tooltipAnnotation = null;
            oneLineAnnotation = null;
            verboseAnnotation = null;
        }

        // do slow activity of loading the item
        v.setResult(new AnnotatedResult(r, annotation, nonHTMLAnnotation,
                oneLineAnnotation, tooltipAnnotation, verboseAnnotation,
                decorator), search, factory);

        // update GUI
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                v.setText(null);
                v.commitResult();
            }
        });
    }

    protected class ResultsGatherer implements Runnable {
        public void run() {
            try {
                while (running) {
                    // wait for next item
                    System.out.println("wait for next item...");
                    Result r = search.getNextResult();
                    System.out.println(" " + r);

                    if (r != null) {
                        // we have data

                        if (isFull()) {
                            // wait
                            synchronized (fullSynchronizer) {
                                if (isFull()) {
                                    setNextEnabledOnAWT(true);
                                }
                                while (isFull()) {
                                    fullSynchronizer.wait();
                                }

                                // no longer full
                                fillNext(r);
                            }
                        } else {
                            // not full
                            fillNext(r);
                        }
                    } else {
                        // no more objects
                        System.out.println("no more objects");
                        running = false;
                    }
                }
            } catch (InterruptedException e) {
                System.out.println("INTERRUPTED !");
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                running = false;

                // clear anything not shown
                setNextEnabledOnAWT(false);

                // clean up
                resultGatherer = null;

                searchRunning = false;
                updateTimers();

                // one more stats
                try {
                    SwingUtilities.invokeAndWait(new Runnable() {
                        public void run() {
                            try {
                                System.out.println("last stats gathering");
                                stats.update(search.getStatistics());
                            } catch (SearchClosedException e) {
                                // ignore
                            } catch (IOException e) {
                                e.printStackTrace();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();
                } catch (InvocationTargetException e1) {
                    e1.printStackTrace();
                }

                System.out.println("FINALLY stopping search");
                try {
                    search.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println(" done");

                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        startButton.setEnabled(true);
                        stopButton.setEnabled(false);
                    }
                });

                System.out.println("done with finally");
            }
        }
    }

    public void stop() throws InterruptedException {
        running = false;
        search.close();

        Thread rg = resultGatherer;
        if (rg != null) {
            // interrupt anything
            rg.interrupt();

            // // wait for exit
            // try {
            // System.out.print("joining...");
            // System.out.flush();
            // resultGatherer.join();
            // System.out.println(" done");
            // } catch (InterruptedException e) {
            // e.printStackTrace();
            // }
        }
    }

    public void updateTimers() {
        if (searchRunning) {
            statsTimer.start();
            if (updateSessionVars) {
                sessionVarsTimerTask = createSessionVarsTimerTask();
                sessionVarsTimer.schedule(sessionVarsTimerTask, 0,
                        sessionVarsInterval);
            } else {
                if (sessionVarsTimerTask != null) {
                    sessionVarsTimerTask.cancel();
                }
            }
        } else {
            statsTimer.stop();
            if (sessionVarsTimerTask != null) {
                sessionVarsTimerTask.cancel();
            }
        }
    }

    public void start(Search s, SearchFactory f) {
        search = s;
        factory = f;

        running = true;

        clearAll();

        stats.setIndeterminateMessage("Initializing Search");

        new Thread(new Runnable() {
            public void run() {
                System.out.println("start search");

                searchRunning = true;
                updateTimers();
                (resultGatherer = new Thread(new ResultsGatherer())).start();
            }
        }).start();
    }

    protected void setNextEnabledOnAWT(final boolean state) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                nextButton.setEnabled(state);
            }
        });
    }

    public void setSessionVariableUpdateInterval(int value) {
        if (value == -1) {
            System.out.println("Stopping session vars timer");
            updateSessionVars = false;
            updateTimers();
        } else {
            System.out.println("Setting session vars timer to " + value);
            updateSessionVars = false;
            updateTimers();
            updateSessionVars = true;
            updateTimers();
        }
    }
}
