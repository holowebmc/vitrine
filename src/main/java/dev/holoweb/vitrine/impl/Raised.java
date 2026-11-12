package dev.holoweb.vitrine.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * The solids a world page raises with {@code --vitrine-depth}: each element's outline, extruded off the panel.
 * The top shows the element, the walls stretch the pixels along its edge (shaded as if lit from above), and the
 * solid stands exactly on the element's own picture on the page, hiding it.
 *
 * <p>Everything here is in page pixels: x right, y down, height out of the page. Built once per report from the
 * page ({@link Native#raised}), which is untrusted: anything malformed is dropped.
 */
final class Raised {
	/** One vertex: where it is, the page pixel it shows, and its brightness (0..255). */
	interface Sink {
		void vertex(float x, float y, float height, float pageX, float pageY, int gray);
	}

	private static final int FLOATS = 6; // per vertex, in Sink order
	private static final float MAX_HEIGHT = 4096;
	// ponytail: fixed; an element thinner than twice this samples past its middle. Make walls a colour the page
	// sets (or the element's background) if stretched pixels ever aren't good enough.
	private static final float INSET = 2.5f;

	private final float[] quads; // 4 vertices each; triangles repeat their last vertex
	private final float[][] outlines; // per solid: x, y per point, clockwise on screen
	private final float[] bottoms, tops;
	/** The highest top. */
	final float height;

	private Raised(float[] quads, float[][] outlines, float[] bottoms, float[] tops, float height) {
		this.quads = quads;
		this.outlines = outlines;
		this.bottoms = bottoms;
		this.tops = tops;
		this.height = height;
	}

	/** {@code report}: per solid, bottom, top, point count, then x, y per point. Null when nothing valid is raised. */
	static Raised of(float[] report) {
		List<float[]> outlines = new ArrayList<>();
		List<float[]> levels = new ArrayList<>();
		int vertices = 0;
		for (int i = 0; i + 3 <= report.length;) {
			float bottom = report[i], top = report[i + 1];
			int n = (int) report[i + 2];
			if (n < 3 || n > 512 || i + 3 + 2 * n > report.length) break; // lost track of the layout: stop
			float[] p = outline(report, i + 3, n);
			i += 3 + 2 * n;
			if (p == null || !(bottom >= 0 && top > bottom && top <= MAX_HEIGHT)) continue;
			outlines.add(p);
			levels.add(new float[] {bottom, top});
			vertices += (p.length / 2 - 2 + p.length / 2) * 4;
		}
		if (outlines.isEmpty()) return null;

		float[] quads = new float[vertices * FLOATS], bottoms = new float[outlines.size()], tops = new float[outlines.size()];
		float height = 0;
		int at = 0;
		for (int s = 0; s < bottoms.length; s++) {
			bottoms[s] = levels.get(s)[0];
			tops[s] = levels.get(s)[1];
			height = Math.max(height, tops[s]);
			at = build(outlines.get(s), bottoms[s], tops[s], quads, at);
		}
		return new Raised(quads, outlines.toArray(new float[0][]), bottoms, tops, height);
	}

	/** The points as a clean clockwise outline (no repeats), or null if that leaves no area. */
	private static float[] outline(float[] report, int from, int n) {
		float[] p = new float[2 * n];
		int m = 0;
		for (int k = 0; k < n; k++) {
			float x = report[from + 2 * k], y = report[from + 2 * k + 1];
			if (!Float.isFinite(x) || !Float.isFinite(y)) return null;
			if (m > 0 && Math.abs(x - p[2 * m - 2]) < 0.01f && Math.abs(y - p[2 * m - 1]) < 0.01f) continue;
			p[2 * m] = x;
			p[2 * m + 1] = y;
			m++;
		}
		if (m > 1 && Math.abs(p[0] - p[2 * m - 2]) < 0.01f && Math.abs(p[1] - p[2 * m - 1]) < 0.01f) m--;
		if (m < 3) return null;
		double area = 0;
		for (int k = 0; k < m; k++) {
			int j = (k + 1) % m;
			area += (double) p[2 * k] * p[2 * j + 1] - (double) p[2 * j] * p[2 * k + 1];
		}
		if (Math.abs(area) < 1) return null;
		float[] out = new float[2 * m];
		for (int k = 0; k < m; k++) { // y points down, so a positive area is clockwise on screen
			int src = area > 0 ? k : m - 1 - k;
			out[2 * k] = p[2 * src];
			out[2 * k + 1] = p[2 * src + 1];
		}
		return out;
	}

	/** Appends one solid's quads at {@code at}; returns the next free index. */
	private static int build(float[] p, float bottom, float top, float[] quads, int at) {
		int n = p.length / 2;
		// The top, counter-clockwise seen from the front like the page's own quad.
		int[] tris = triangulate(p, n);
		for (int t = 0; t < tris.length; t += 3) {
			int a = tris[t], b = tris[t + 1], c = tris[t + 2];
			for (int v : new int[] {a, c, b, b}) at = put(quads, at, p[2 * v], p[2 * v + 1], top, p[2 * v], p[2 * v + 1], 255);
		}
		// Outward normal of each edge k -> k+1.
		float[] nx = new float[n], ny = new float[n];
		for (int k = 0; k < n; k++) {
			int j = (k + 1) % n;
			float dx = p[2 * j] - p[2 * k], dy = p[2 * j + 1] - p[2 * k + 1], len = (float) Math.hypot(dx, dy);
			nx[k] = dy / len;
			ny[k] = -dx / len;
		}
		// The walls. Each shows the pixels just inside the outline, all the way down.
		for (int k = 0; k < n; k++) {
			int j = (k + 1) % n, before = (k + n - 1) % n;
			float ax = p[2 * k], ay = p[2 * k + 1], bx = p[2 * j], by = p[2 * j + 1];
			float[] ta = inset(p, k, nx[before], ny[before], nx[k], ny[k]), tb = inset(p, j, nx[k], ny[k], nx[j], ny[j]);
			int ga = gray(nx[before], ny[before], nx[k], ny[k]), gb = gray(nx[j], ny[j], nx[k], ny[k]);
			at = put(quads, at, ax, ay, top, ta[0], ta[1], ga);
			at = put(quads, at, bx, by, top, tb[0], tb[1], gb);
			at = put(quads, at, bx, by, bottom, tb[0], tb[1], gb);
			at = put(quads, at, ax, ay, bottom, ta[0], ta[1], ga);
		}
		return at;
	}

	private static int put(float[] quads, int at, float x, float y, float height, float pageX, float pageY, int gray) {
		quads[at] = x;
		quads[at + 1] = y;
		quads[at + 2] = height;
		quads[at + 3] = pageX;
		quads[at + 4] = pageY;
		quads[at + 5] = gray;
		return at + FLOATS;
	}

	/**
	 * Point {@code k} moved into the outline until it is {@link #INSET} pixels from both its edges (a miter),
	 * past the antialiased rim, so walls take the element's solid colour instead of streaks of its edge.
	 */
	private static float[] inset(float[] p, int k, float nx1, float ny1, float nx2, float ny2) {
		// (n1 + n2) / (1 + n1.n2) is exactly 1 away from both edges; the floor keeps sharp tips from shooting across.
		float by = INSET / Math.max(1 + nx1 * nx2 + ny1 * ny2, 0.5f);
		return new float[] {p[2 * k] - (nx1 + nx2) * by, p[2 * k + 1] - (ny1 + ny2) * by};
	}

	/**
	 * Brightness of a wall at one of its ends: lit from above, so walls facing up are light and facing down dark.
	 * Where the neighbouring wall (normal {@code ox, oy}) turns only a little, both share the shade and a curve
	 * looks round; at a real corner each wall keeps its own.
	 */
	private static int gray(float ox, float oy, float nx, float ny) {
		float y = ox * nx + oy * ny > 0.8f ? (oy + ny) / (float) Math.hypot(ox + nx, oy + ny) : ny;
		return Math.round(184 - 40 * y);
	}

	/** Ear clipping of a clockwise outline: 3 point indices per triangle, clockwise. */
	private static int[] triangulate(float[] p, int n) {
		int[] left = new int[n], tris = new int[(n - 2) * 3];
		for (int k = 0; k < n; k++) left[k] = k;
		int m = n, t = 0;
		while (m > 3) {
			int ear = 0; // an outline that crosses itself may have none: cut anywhere, it still ends
			search:
			for (int k = 0; k < m; k++) {
				int a = left[(k + m - 1) % m], b = left[k], c = left[(k + 1) % m];
				if (side(p, a, b, c) <= 0) continue; // not a convex corner
				for (int q = 0; q < m; q++) {
					int o = left[q];
					if (o != a && o != b && o != c && side(p, a, b, o) >= 0 && side(p, b, c, o) >= 0 && side(p, c, a, o) >= 0) continue search;
				}
				ear = k;
				break;
			}
			tris[t++] = left[(ear + m - 1) % m];
			tris[t++] = left[ear];
			tris[t++] = left[(ear + 1) % m];
			System.arraycopy(left, ear + 1, left, ear, m - ear - 1);
			m--;
		}
		tris[t++] = left[0];
		tris[t++] = left[1];
		tris[t] = left[2];
		return tris;
	}

	/** Positive when point {@code c} is on the inner side of edge a -> b of a clockwise outline. */
	private static double side(float[] p, int a, int b, int c) {
		return (double) (p[2 * b] - p[2 * a]) * (p[2 * c + 1] - p[2 * a + 1]) - (double) (p[2 * b + 1] - p[2 * a + 1]) * (p[2 * c] - p[2 * a]);
	}

	void draw(Sink sink) {
		for (int i = 0; i < quads.length; i += FLOATS) {
			sink.vertex(quads[i], quads[i + 1], quads[i + 2], quads[i + 3], quads[i + 4], (int) quads[i + 5]);
		}
	}

	/**
	 * Where a ray lands on the solids: {height, pageX, pageY} of the first thing it meets coming down, or null if
	 * it reaches the page untouched. The ray is over page pixel {@code (x + slopeX * h, y + slopeY * h)} at height h.
	 * Hitting a wall answers the element's edge there, which is what the wall shows.
	 */
	double[] hit(double x, double y, double slopeX, double slopeY) {
		double[] best = null;
		for (int s = 0; s < outlines.length; s++) {
			float[] p = outlines[s];
			double top = tops[s], bottom = bottoms[s];
			if (best != null && best[0] >= top) continue;
			double tx = x + slopeX * top, ty = y + slopeY * top;
			if (inside(p, tx, ty)) {
				best = new double[] {top, tx, ty};
				continue;
			}
			// Not through the top: follow the ray down to the solid's bottom and take the first wall it crosses.
			double rx = slopeX * (bottom - top), ry = slopeY * (bottom - top), first = 2;
			for (int k = 0, n = p.length / 2; k < n; k++) {
				int j = (k + 1) % n;
				double ex = p[2 * j] - p[2 * k], ey = p[2 * j + 1] - p[2 * k + 1], den = rx * ey - ry * ex;
				if (den == 0) continue;
				double f = ((p[2 * k] - tx) * ey - (p[2 * k + 1] - ty) * ex) / den, g = ((p[2 * k] - tx) * ry - (p[2 * k + 1] - ty) * rx) / den;
				if (f >= 0 && f <= 1 && g >= 0 && g <= 1 && f < first) first = f;
			}
			if (first > 1) continue;
			double h = top + (bottom - top) * first, len = Math.hypot(rx, ry);
			if (best == null || h > best[0]) best = new double[] {h, tx + rx * first + rx / len * 0.5, ty + ry * first + ry / len * 0.5}; // half a pixel in
		}
		return best;
	}

	private static boolean inside(float[] p, double x, double y) {
		boolean in = false;
		for (int k = 0, n = p.length / 2, j = n - 1; k < n; j = k++) {
			double ax = p[2 * k], ay = p[2 * k + 1], bx = p[2 * j], by = p[2 * j + 1];
			if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) in = !in;
		}
		return in;
	}

	/** Self-check: {@code java -cp versions/<mc>/build/classes/java/main dev.holoweb.vitrine.impl.Raised}. */
	public static void main(String[] args) {
		// A 10x10 square, 5 high, given counter-clockwise (must be turned around).
		Raised box = of(new float[] {0, 5, 4, 0, 0, 0, 10, 10, 10, 10, 0});
		check(box != null && box.height == 5, "square is raised");
		check(box.quads.length == (2 + 4) * 4 * FLOATS, "square: 2 triangles + 4 walls");
		check(box.hit(5, 5, 0, 0)[0] == 5, "straight down on the top");
		check(box.hit(20, 5, 0, 0) == null, "straight down beside it");
		check(box.hit(18, 5, -1, 0) == null, "slanted, passing beside it");
		double[] wall = box.hit(8, 5, 1, 0); // over x=13 at the top, x=8 on the page: enters through the x=10 wall
		check(wall != null && Math.abs(wall[0] - 2) < 1e-6 && Math.abs(wall[1] - 9.5) < 1e-6, "slanted, into a wall");

		// An L (concave): its triangles must cover exactly its area, 75.
		float[] l = {0, 0, 10, 0, 10, 5, 5, 5, 5, 10, 0, 10};
		int[] tris = triangulate(l, 6);
		double area = 0;
		for (int t = 0; t < tris.length; t += 3) {
			double a = side(l, tris[t], tris[t + 1], tris[t + 2]) / 2;
			check(a > 0, "L: triangles keep the outline's direction");
			area += a;
		}
		check(Math.abs(area - 75) < 1e-6, "L: triangles cover it");

		// A child standing on the box wins where both are under the ray.
		Raised nested = of(new float[] {0, 5, 4, 0, 0, 10, 0, 10, 10, 0, 10, 5, 8, 3, 4, 4, 6, 4, 5, 6});
		check(nested.hit(5, 4.5, 0, 0)[0] == 8 && nested.hit(1, 1, 0, 0)[0] == 5, "nested: the higher top wins");

		// Garbage from a page: dropped, never thrown.
		check(of(new float[] {0, 5, 4, 0, 0}) == null, "cut short");
		check(of(new float[] {0, 5, 3, 0, 0, Float.NaN, 0, 5, 5}) == null, "NaN point");
		check(of(new float[] {0, 5, 3, 1, 1, 1, 1, 1, 1}) == null, "no area");
		check(of(new float[] {5, 5, 3, 0, 0, 9, 0, 0, 9}) == null, "no height");
		check(of(new float[] {0, 5, 5, 0, 0, 9, 9, 9, 0, 0, 9, 4, 4}) != null, "crossing outline still ends");
		System.out.println("Raised: ok");
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
