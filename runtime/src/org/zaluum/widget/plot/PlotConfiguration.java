package org.zaluum.widget.plot;

import info.monitorenter.gui.chart.Chart2D;
import info.monitorenter.gui.chart.IAxis;
import info.monitorenter.gui.chart.IRangePolicy;
import info.monitorenter.gui.chart.ITrace2D;
import info.monitorenter.gui.chart.ITracePainter;
import info.monitorenter.gui.chart.axis.AxisLog10;
import info.monitorenter.gui.chart.traces.painters.TracePainterFill;
import info.monitorenter.gui.chart.traces.painters.TracePainterVerticalBar;

import java.awt.BasicStroke;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

public class PlotConfiguration {
	Map<IAxis, String> map = new HashMap<IAxis, String>();
	private StringWriter stringWriter;
	private PrintWriter p;
	private Chart2D c;

	public String javaScriptConfigure(Chart2D c) {
		this.c = c;
		stringWriter = new StringWriter();
		p = new PrintWriter(stringWriter);
		p.format("c.setMinPaintLatency(%d);\n", c.getMinPaintLatency());
		p.format("c.removeAllTraces();\n");
		p.format("c.removeAxisXBottom(c.getAxisX());\n");
		p.format("c.removeAxisYLeft(c.getAxisY());\n");
		p.format("c.setPaintLabels(%b);\n", c.isPaintLabels());
		p.format("c.setUseAntialiasing(%b);\n", c.isUseAntialiasing());
		int i = 0;
		for (IAxis a : c.getAxesXBottom())
			doAxis(i++, "addAxisXBottom", a, p, c);
		for (IAxis a : c.getAxesXTop())
			doAxis(i++, "addAxisXTop", a, p, c);
		for (IAxis a : c.getAxesYLeft())
			doAxis(i++, "addAxisYLeft", a, p, c);
		for (IAxis a : c.getAxesYRight())
			doAxis(i++, "addAxisYRight", a, p, c);
		for (ITrace2D t : c.getTraces())
			doTrace(t);
		return stringWriter.toString();
	}

	public void doTrace(ITrace2D t) {
		p.format(
				"var t = new Packages.info.monitorenter.gui.chart.traces.Trace2DLtd(%d,\"%s\");\n",
				t.getMaxSize(), StringEscapeUtils.escapeJavaScript(t.getName()));
		IAxis axisX = c.getAxisX(t);
		IAxis axisY = c.getAxisY(t);
		p.format("c.addTrace(t, %s, %s);\n", map.get(axisX), map.get(axisY));
		p.format("t.setZIndex(%d);", t.getZIndex().intValue());
		p.format("t.setColor(new java.awt.Color(%d));\n", t.getColor().getRGB());
		if (t.getTracePainters().size() == 1) {
			ITracePainter<?> painter = t.getTracePainters().iterator().next();
			if (painter instanceof TracePainterVerticalBar
					|| painter instanceof TracePainterFill)
				p.format("t.setTracePainter(new Packages.%s(c));\n", painter
						.getClass().getName());
			else
				p.format("t.setTracePainter(new Packages.%s());\n", painter
						.getClass().getName());

		}

		if (t.getStroke() != null && t.getStroke() instanceof BasicStroke) {
			BasicStroke b = (BasicStroke) t.getStroke();
			float[] arr = b.getDashArray();
			if (arr != null) {
				p.format(
						"var dash = java.lang.reflect.Array.newInstance(java.lang.Float.TYPE,%d);\n",
						arr.length);
				for (int i = 0; i < arr.length; i++)
					p.format("dash[%d]=%s;\n", i, "" + arr[i]);
			} else {
				p.format("var dash=null;\n");
			}
			p.format(
					"t.setStroke(new java.awt.BasicStroke(%s, %d, %d, %s, dash, %s));\n",
					"" + b.getLineWidth(), b.getEndCap(), b.getLineJoin(), ""
							+ b.getMiterLimit(), "" + b.getDashPhase());
		}

	}

	public void doAxis(int i, String addStr, IAxis axis, PrintWriter p,
			Chart2D c) {
		String name = "axis" + i;
		if (axis instanceof AxisLog10)
			p.format(
					"var %s = new Packages.info.monitorenter.gui.chart.axis.AxisLog10();\n",
					name);
		else
			p.format(
					"var %s= new Packages.info.monitorenter.gui.chart.axis.AxisLinear();\n",
					name);
		IRangePolicy rangePolicy = axis.getRangePolicy();
		p.format("var policy = new Packages.%s();\n", rangePolicy.getClass()
				.getName());
		p.format("c.%s(%s);\n", addStr, name);
		if (axis.getFormatter() instanceof LabelFormatterDecimal) {
			String pattern = ((LabelFormatterDecimal) axis.getFormatter())
					.toPattern();
			p.format("var format = new java.text.DecimalFormat(\"%s\");\n",
					StringEscapeUtils.escapeJavaScript(pattern));
			p.format(
					"%s.setFormatter(new Packages.org.zaluum.widget.plot.LabelFormatterDecimal(format));\n",
					name);
		}
		p.format(
				"policy.setRange(new Packages.info.monitorenter.util.Range(%s,%s));\n",
				"" + rangePolicy.getRange().getMin(), ""
						+ rangePolicy.getRange().getMax());
		p.format("%s.setRangePolicy(policy);", name);
		p.format("%s.getAxisTitle().setVisible(%b);\n", name, axis
				.getAxisTitle().isVisible());
		p.format("%s.setTitle(\"%s\");\n", name, StringEscapeUtils
				.escapeJavaScript(axis.getAxisTitle().getTitle()));
		p.format("%s.setPaintScale(%b);\n", name, axis.isPaintScale());
		p.format("%s.setPaintGrid(%b);\n", name, axis.isPaintGrid());
		p.format("%s.setVisible(%b);\n", name, axis.isVisible());
		map.put(axis, name);
	}
}
