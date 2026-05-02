package com.mimicenzymes.litematicafiller.core;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

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
        root = buildRecursive(new ArrayList<>(points), 0, 0, points.size());
    }

    private Node buildRecursive(List<BlockPos> points, int depth, int start, int end)
    {
        if (start >= end)
        {
            return null;
        }

        int axis = depth % 3;

        points.subList(start, end).sort((a, b) ->
        {
            if (axis == 0) return Integer.compare(a.getX(), b.getX());
            if (axis == 1) return Integer.compare(a.getY(), b.getY());
            return Integer.compare(a.getZ(), b.getZ());
        });

        int mid = start + (end - start) / 2;

        Node node = new Node(points.get(mid), axis);

        node.left = buildRecursive(points, depth + 1, start, mid);
        node.right = buildRecursive(points, depth + 1, mid + 1, end);

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
