package mchorse.bbs_mod.particles;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.Variable;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.MathUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ParticleCurve
{
    public ParticleCurveType type = ParticleCurveType.LINEAR;
    public List<MolangExpression> nodes = new ArrayList<>();
    public MolangExpression input = MolangParser.ZERO;
    public MolangExpression range = MolangParser.ZERO;
    public Variable variable;

    /* Bezier chain nodes: key=time, value=node data */
    public TreeMap<Float, BezierChainNode> bezierChainNodes = new TreeMap<>();

    /* Bezier editor: X positions of control points (0..1 range) for visual editing */
    public float bezierCP1X = 0.33F;
    public float bezierCP2X = 0.66F;
    public float bezierCP3X = 0.33F;
    public float bezierCP4X = 0.66F;

    public ParticleCurve()
    {
        this.nodes.add(MolangParser.ZERO);
        this.nodes.add(MolangParser.ONE);
        this.nodes.add(MolangParser.ZERO);
    }

    public double compute()
    {
        double input = this.input.get();

        if (this.type == ParticleCurveType.BEZIER_CHAIN)
        {
            return this.computeCurve(input);
        }

        double range = this.range.get();

        return this.computeCurve(range == 0 ? 0 : input / range);
    }

    private double computeCurve(double factor)
    {
        int length = this.nodes.size();

        if (this.type == ParticleCurveType.BEZIER_CHAIN)
        {
            return this.computeBezierChain(factor);
        }

        if (length == 0)
        {
            return 0;
        }
        else if (length == 1)
        {
            return this.nodes.get(0).get();
        }

        if (factor < 0)
        {
            factor = -(1 + factor);
        }

        factor = MathUtils.clamp(factor, 0, 1);

        if (this.type == ParticleCurveType.HERMITE)
        {
            if (length <= 3)
            {
                return this.nodes.get(length - 2).get();
            }

            factor *= (length - 3);
            int index = (int) factor + 1;

            MolangExpression beforeFirst = this.getNode(index - 1);
            MolangExpression first = this.getNode(index);
            MolangExpression next = this.getNode(index + 1);
            MolangExpression afterNext = this.getNode(index + 2);

            return Lerps.cubicHermite(beforeFirst.get(), first.get(), next.get(), afterNext.get(), factor % 1);
        }
        else if (this.type == ParticleCurveType.BEZIER)
        {
            /* Bezier: 4 nodes per segment [P0, P1, P2, P3] — cubic bezier
             * P0=start value, P1=control point 1 value, P2=control point 2 value, P3=end value
             * Multiple segments are supported: each 4 nodes = one segment */
            if (length < 4)
            {
                return this.nodes.get(0).get();
            }

            int segments = length / 4;
            double segFactor = factor * segments;
            int segIndex = (int) segFactor;

            if (segIndex >= segments)
            {
                segIndex = segments - 1;
            }

            double localT = segFactor - segIndex;
            int base = segIndex * 4;

            double y0 = this.nodes.get(base).get();
            double y1 = this.nodes.get(base + 1).get();
            double y2 = this.nodes.get(base + 2).get();
            double y3 = this.nodes.get(base + 3).get();

            return Lerps.bezier(y0, y1, y2, y3, localT);
        }

        factor *= length - 1;
        int index = (int) factor;

        MolangExpression first = this.getNode(index);
        MolangExpression next = this.getNode(index + 1);

        return Lerps.lerp(first.get(), next.get(), factor % 1);
    }

    private double computeBezierChain(double factor)
    {
        if (this.bezierChainNodes.isEmpty())
        {
            return 0;
        }

        factor = MathUtils.clamp(factor, 0, 1);

        Map.Entry<Float, BezierChainNode> first = this.bezierChainNodes.firstEntry();
        Map.Entry<Float, BezierChainNode> last = this.bezierChainNodes.lastEntry();

        if (factor <= first.getKey())
        {
            return first.getValue().leftValue;
        }

        if (factor >= last.getKey())
        {
            return last.getValue().rightValue;
        }

        Map.Entry<Float, BezierChainNode> prev = null;

        for (Map.Entry<Float, BezierChainNode> entry : this.bezierChainNodes.entrySet())
        {
            if (entry.getKey() >= factor)
            {
                if (prev == null)
                {
                    return entry.getValue().leftValue;
                }

                float t0 = prev.getKey();
                float t1 = entry.getKey();
                float localT = (float) ((factor - t0) / (t1 - t0));

                BezierChainNode n0 = prev.getValue();
                BezierChainNode n1 = entry.getValue();

                double y0 = n0.rightValue;
                double y1 = n1.leftValue;
                double cp0 = y0 + n0.rightSlope * (t1 - t0);
                double cp1 = y1 - n1.leftSlope * (t1 - t0);

                return Lerps.bezier(y0, cp0, cp1, y1, localT);
            }

            prev = entry;
        }

        return last.getValue().rightValue;
    }

    /**
     * Bezier chain node: stores value and slope data per time point
     */
    public static class BezierChainNode
    {
        public float leftValue;
        public float rightValue;
        public float leftSlope;
        public float rightSlope;

        public BezierChainNode(float leftValue, float rightValue, float leftSlope, float rightSlope)
        {
            this.leftValue = leftValue;
            this.rightValue = rightValue;
            this.leftSlope = leftSlope;
            this.rightSlope = rightSlope;
        }
    }

    public void ensureDefaultBezierChainNodes()
    {
        if (!this.bezierChainNodes.isEmpty())
        {
            return;
        }

        this.bezierChainNodes.put(0F, new BezierChainNode(0F, 0F, 0F, 0F));
        this.bezierChainNodes.put(1F, new BezierChainNode(1F, 1F, 0F, 0F));
    }

    private MolangExpression getNode(int index)
    {
        if (index < 0)
        {
            return this.nodes.get(0);
        }
        else if (index >= this.nodes.size())
        {
            return this.nodes.get(this.nodes.size() - 1);
        }

        return this.nodes.get(index);
    }

    public MapType toData()
    {
        MapType curve = new MapType();

        curve.putString("type", this.type.id);

        if (this.type == ParticleCurveType.BEZIER_CHAIN)
        {
            MapType nodesMap = new MapType();

            for (Map.Entry<Float, BezierChainNode> entry : this.bezierChainNodes.entrySet())
            {
                MapType node = new MapType();
                BezierChainNode n = entry.getValue();

                if (n.leftValue == n.rightValue)
                {
                    node.putFloat("value", n.leftValue);
                }
                else
                {
                    node.putFloat("left_value", n.leftValue);
                    node.putFloat("right_value", n.rightValue);
                }

                if (n.leftSlope == n.rightSlope)
                {
                    node.putFloat("slope", n.leftSlope);
                }
                else
                {
                    node.putFloat("left_slope", n.leftSlope);
                    node.putFloat("right_slope", n.rightSlope);
                }

                nodesMap.put(String.valueOf(entry.getKey()), node);
            }

            curve.put("nodes", nodesMap);
        }
        else
        {
            ListType nodes = new ListType();

            for (MolangExpression expression : this.nodes)
            {
                nodes.add(expression.toData());
            }

            curve.put("nodes", nodes);
        }

        curve.put("input", this.input.toData());

        if (this.type != ParticleCurveType.BEZIER_CHAIN)
        {
            curve.put("horizontal_range", this.range.toData());
        }

        return curve;
    }

    public void fromData(MapType data, MolangParser parser) throws MolangException
    {
        if (data.has("type")) this.type = ParticleCurveType.fromString(data.getString("type"));
        if (data.has("input")) this.input = parser.parseDataSilently(data.get("input"));
        if (data.has("horizontal_range")) this.range = parser.parseDataSilently(data.get("horizontal_range"));

        if (data.has("nodes"))
        {
            BaseType nodesData = data.get("nodes");

            if (this.type == ParticleCurveType.BEZIER_CHAIN && nodesData.isMap())
            {
                this.bezierChainNodes.clear();

                for (Map.Entry<String, BaseType> entry : nodesData.asMap())
                {
                    float time = Float.parseFloat(entry.getKey());

                    if (entry.getValue().isMap())
                    {
                        MapType nodeData = entry.getValue().asMap();
                        float value = nodeData.getFloat("value");
                        float slope = nodeData.getFloat("slope");
                        float leftValue = nodeData.has("left_value") ? nodeData.getFloat("left_value") : value;
                        float rightValue = nodeData.has("right_value") ? nodeData.getFloat("right_value") : value;
                        float leftSlope = nodeData.has("left_slope") ? nodeData.getFloat("left_slope") : slope;
                        float rightSlope = nodeData.has("right_slope") ? nodeData.getFloat("right_slope") : slope;

                        this.bezierChainNodes.put(time, new BezierChainNode(leftValue, rightValue, leftSlope, rightSlope));
                    }
                }

                this.ensureDefaultBezierChainNodes();
            }
            else if (nodesData.isList())
            {
                ListType nodes = nodesData.asList();

                this.nodes.clear();

                for (int i = 0, c = nodes.size(); i < c; i ++)
                {
                    this.nodes.add(parser.parseDataSilently(nodes.get(i), MolangParser.ONE));
                }
            }
        }

        if (this.type == ParticleCurveType.BEZIER_CHAIN)
        {
            this.ensureDefaultBezierChainNodes();
        }
    }
}
