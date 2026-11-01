package net.officefloor.sinewslim.intellij;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.ui.JBColor;
import com.intellij.util.Alarm;

import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.core.Pieces;
import net.officefloor.sinewslim.core.Run;
import net.officefloor.sinewslim.core.SlimResult;

/**
 * The proposal for one method drawn over it in the editor (DESIGN.md, Surface 3): each piece a
 * coloured band with a line above it saying what it takes and returns, and the buttons
 * <em>Extract…</em> and <em>Skip</em>; the method's verdict and the remainder above the method,
 * with <em>Close</em>. One overlay per editor: showing another method's replaces it.
 *
 * <p>
 * Skipping a piece leaves it out and solves again, since the cheapest segmentation without it
 * may differ. Any edit to the document redraws the overlay from the new text, so after an
 * extraction it shows what is left to do, or closes when the method is under the cap.
 */
public final class SlimOverlay implements Disposable {

	static final Key<SlimOverlay> KEY = Key.create("sinew-slim.overlay");

	/** The pieces alternate two bands, so neighbours stay apart. */
	static final JBColor[] BANDS = { new JBColor(new Color(0x0969da, true), new Color(0x4493f8, true)),
			new JBColor(new Color(0x8250df, true), new Color(0xab7df8, true)) };

	static Color band(int i, int alpha) {
		Color c = BANDS[i % BANDS.length];
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
	}

	private final Project project;
	private final Editor editor;
	private final SmartPsiElementPointer<PsiMethod> method;
	private final Set<Run> excluded = new HashSet<>();
	private final List<RangeHighlighter> highlighters = new ArrayList<>();
	private final List<Inlay<SlimInlayRenderer>> inlays = new ArrayList<>();
	private final Alarm redraw;
	/** What the last draw showed, for the tests and the actions. */
	private Assessment shown;

	private SlimOverlay(Project project, Editor editor, PsiMethod method) {
		this.project = project;
		this.editor = editor;
		this.method = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method);
		this.redraw = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
		editor.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void documentChanged(@NotNull DocumentEvent event) {
				redraw.cancelAllRequests();
				redraw.addRequest(SlimOverlay.this::draw, 300);
			}
		}, this);
		editor.addEditorMouseListener(new EditorMouseListener() {
			@Override
			public void mouseClicked(@NotNull EditorMouseEvent e) {
				Inlay<?> inlay = e.getInlay();
				if (inlay == null || !(inlay.getRenderer() instanceof SlimInlayRenderer r) || !inlays.contains(inlay)) {
					return;
				}
				Rectangle bounds = inlay.getBounds();
				if (bounds == null) {
					return;
				}
				Point p = e.getMouseEvent().getPoint();
				SlimInlayRenderer.Button b = r.buttonAt(p.x - bounds.x, p.y - bounds.y);
				if (b != null) {
					e.consume();
					b.action().run();
				}
			}
		}, this);
		if (editor instanceof Disposable d) {
			Disposer.register(d, this);
		}
	}

	/** Show the proposal for a method, replacing any overlay this editor has; the same method again closes it. */
	public static SlimOverlay toggle(Project project, Editor editor, PsiMethod method) {
		SlimOverlay current = editor.getUserData(KEY);
		if (current != null) {
			boolean same = method.equals(current.method.getElement());
			Disposer.dispose(current);
			if (same) {
				return null;
			}
		}
		return show(project, editor, method);
	}

	public static SlimOverlay show(Project project, Editor editor, PsiMethod method) {
		SlimOverlay current = editor.getUserData(KEY);
		if (current != null) {
			Disposer.dispose(current);
		}
		SlimOverlay o = new SlimOverlay(project, editor, method);
		editor.putUserData(KEY, o);
		Disposer.register(project, o);
		o.draw();
		return o;
	}

	public static SlimOverlay of(Editor editor) {
		return editor.getUserData(KEY);
	}

	Assessment shown() {
		return shown;
	}

	List<Inlay<SlimInlayRenderer>> inlays() {
		return inlays;
	}

	/** Draw from the document as it stands: the method's verdict, and its pieces with their buttons. */
	void draw() {
		if (editor.isDisposed()) {
			Disposer.dispose(this);
			return;
		}
		clear();
		PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
		PsiMethod m = method.getElement();
		SlimService.MethodAnalysis ma = m == null ? null : Psi.analysis(project, editor.getDocument(), m);
		if (ma == null || ma.assessment().status() == Status.UNDER_CAP) {
			Disposer.dispose(this); // gone, or slimmed under the cap
			return;
		}
		Assessment a = excluded.isEmpty() ? ma.assessment() : SlimService.get(project).reassess(ma, excluded);
		shown = a;
		Document doc = editor.getDocument();
		int length = ma.unit().facts().length();
		SlimResult r = a.proposal();
		boolean pfr = SlimService.get(project).config().parameteriseFieldReads();

		String head = "SinewSlim · " + length + " lines · " + Pieces.verdict(a, length)
				+ (r.feasible() ? " · remainder " + Pieces.remainder(r, length) : "")
				+ (excluded.isEmpty() ? "" : " · " + excluded.size() + " skipped");
		List<SlimInlayRenderer.Button> headButtons = new ArrayList<>();
		if (!excluded.isEmpty()) {
			headButtons.add(new SlimInlayRenderer.Button("Reset", () -> {
				excluded.clear();
				draw();
			}));
		}
		headButtons.add(new SlimInlayRenderer.Button("Close", () -> Disposer.dispose(this)));
		addInlay(ma.unit().declLine(), head, null, headButtons);

		if (!r.feasible()) {
			return;
		}
		List<Pieces.Piece> pieces = Pieces.of(r, pfr);
		for (int i = 0; i < pieces.size(); i++) {
			Pieces.Piece p = pieces.get(i);
			int start = doc.getLineStartOffset(p.lo() - 1), end = doc.getLineEndOffset(p.hi() - 1);
			TextAttributes t = new TextAttributes();
			t.setBackgroundColor(band(i, 28));
			highlighters.add(editor.getMarkupModel().addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1, t,
					HighlighterTargetArea.LINES_IN_RANGE));
			List<SlimInlayRenderer.Button> buttons = new ArrayList<>();
			String label;
			if (p.outputs().size() > 1) {
				buttons.add(new SlimInlayRenderer.Button("Extract with record…", () -> extractWithRecord(p)));
			} else {
				buttons.add(new SlimInlayRenderer.Button("Extract…", () -> extract(p)));
			}
			label = "piece " + (i + 1) + " · lines " + p.lo() + "-" + p.hi() + " · " + p.summary(" · ");
			buttons.add(new SlimInlayRenderer.Button("Skip", () -> {
				excluded.add(Run.of(p.seam()));
				draw();
			}));
			addInlay(p.lo(), label, band(i, 160), buttons);
		}
	}

	/** Ask for the method's and the record's names, then extract the piece returning the record. */
	void extractWithRecord(Pieces.Piece piece) {
		PsiMethod m = method.getElement();
		if (m == null) {
			return;
		}
		RecordNamesDialog names = new RecordNamesDialog(project, piece.lo(), piece.hi(), String.join(", ", piece.outputs()),
				"compute" + capitalise(piece.outputs().get(0)), RecordExtractor.suggestName(piece.outputs()));
		if (!names.showAndGet()) {
			return;
		}
		refused(piece, RecordExtractor.extract(project, editor, m, piece, names.methodName(), names.recordName()));
	}

	private void addInlay(int line, String text, Color stripe, List<SlimInlayRenderer.Button> buttons) {
		Document doc = editor.getDocument();
		int offset = doc.getLineStartOffset(line - 1);
		CharSequence chars = doc.getCharsSequence();
		int indent = 0;
		while (offset + indent < chars.length() && (chars.charAt(offset + indent) == ' ' || chars.charAt(offset + indent) == '\t')) {
			indent += chars.charAt(offset + indent) == '\t' ? editor.getSettings().getTabSize(project) : 1;
		}
		Inlay<SlimInlayRenderer> inlay = editor.getInlayModel().addBlockElement(offset, false, true, 1,
				new SlimInlayRenderer(text, indent, stripe, buttons));
		if (inlay != null) {
			inlays.add(inlay);
		}
	}

	/** Ask for the name, then extract the piece through the IDE's Extract Method. */
	void extract(Pieces.Piece piece) {
		PsiMethod m = method.getElement();
		if (m == null) {
			return;
		}
		String suggestion = piece.outputs().size() == 1 ? "compute" + capitalise(piece.outputs().get(0)) : "";
		String name = Messages.showInputDialog(project,
				"Name for the method extracted from lines " + piece.lo() + "-" + piece.hi() + ":", "SinewSlim: Extract Method",
				null, suggestion, new com.intellij.openapi.ui.InputValidator() {
					@Override
					public boolean checkInput(String s) {
						return PsiNameHelper.getInstance(project).isIdentifier(s);
					}

					@Override
					public boolean canClose(String s) {
						return checkInput(s);
					}
				});
		if (name == null) {
			return;
		}
		refused(piece, SlimExtractor.extract(project, editor, m, piece, name));
	}

	/** After an extraction: when it was refused, say why, leave the run out and solve again. */
	private void refused(Pieces.Piece piece, String refused) {
		if (refused == null) {
			return;
		}
		// the IDE knows better than the syntactic facts: leave the run out and solve again
		excluded.add(Run.of(piece.seam()));
		Messages.showWarningDialog(project, "Lines " + piece.lo() + "-" + piece.hi() + " were not extracted: " + refused
				+ "\nSinewSlim has left that run out and solved again.", "SinewSlim");
		SlimService.LOG.info("SinewSlim: lines " + piece.lo() + "-" + piece.hi() + " of " + file() + " not extracted: " + refused);
		draw();
	}

	private String file() {
		VirtualFile f = FileDocumentManager.getInstance().getFile(editor.getDocument());
		return f == null ? "?" : f.getPath();
	}

	static String capitalise(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private void clear() {
		for (RangeHighlighter h : highlighters) {
			h.dispose();
		}
		highlighters.clear();
		for (Inlay<?> i : inlays) {
			Disposer.dispose(i);
		}
		inlays.clear();
	}

	@Override
	public void dispose() {
		if (ApplicationManager.getApplication().isDispatchThread()) {
			clear();
		}
		if (editor.getUserData(KEY) == this) {
			editor.putUserData(KEY, null);
		}
	}
}
