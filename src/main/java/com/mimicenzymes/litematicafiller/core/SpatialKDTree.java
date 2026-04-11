package com.mimicenzymes.litematicafiller.core;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

public class SpatialKDTree
{
    private static class Node
    {
        BlockPos pos;
        Node left;
        Node right;
        int axis;

        Node(BlockPos pos, int axis)
        {
            this.pos = pos;
            this.axis = axis;
        }
    }

    private Node root;

    public void build(List<BlockPos> points)
    {
        root = buildRecursive(points, 0);
    }

    private Node buildRecursive(List<BlockPos> points, int depth)
    {
        if (points.isEmpty())
        {
            return null;
        }

        int axis = depth % 3;

        points.sort((a, b) ->
        {
            if (axis == 0) return Integer.compare(a.getX(), b.getX());
            if (axis == 1) return Integer.compare(a.getY(), b.getY());
            return Integer.compare(a.getZ(), b.getZ());
        });

        int mid = points.size() / 2;

        Node node = new Node(points.get(mid), axis);

        node.left = buildRecursive(new ArrayList<>(points.subList(0, mid)), depth + 1);
        node.right = buildRecursive(points.subList(mid + 1, points.size()), depth + 1);

        return node;
    }

    public List<BlockPos> queryRadius(double x, double y, double z, double radius)
    {
        List<BlockPos> result = new ArrayList<>();
        queryRecursive(root, x, y, z, radius * radius, result);
        return result;
    }

    private void queryRecursive(Node node, double x, double y, double z, double radiusSq, List<BlockPos> result)
    {
        if (node == null)
        {
            return;
        }

        double dx = node.pos.getX() - x;
        double dy = node.pos.getY() - y;
        double dz = node.pos.getZ() - z;

        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq <= radiusSq)
        {
            result.add(node.pos);
        }

        double delta;

        if (node.axis == 0) delta = dx;
        else if (node.axis == 1) delta = dy;
        else delta = dz;

        if (delta > 0)
        {
            queryRecursive(node.left, x, y, z, radiusSq, result);
            if (delta * delta < radiusSq)
                queryRecursive(node.right, x, y, z, radiusSq, result);
        }
        else
        {
            queryRecursive(node.right, x, y, z, radiusSq, result);
            if (delta * delta < radiusSq)
                queryRecursive(node.left, x, y, z, radiusSq, result);
        }
    }
}