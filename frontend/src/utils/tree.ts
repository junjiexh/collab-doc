import type { DocumentMeta } from "../api";

export interface TreeNode extends DocumentMeta {
  children: TreeNode[];
}

export function buildTree(flatList: DocumentMeta[]): TreeNode[] {
  const map = new Map<string, TreeNode>();
  const roots: TreeNode[] = [];

  for (const doc of flatList) {
    map.set(doc.id, { ...doc, children: [] });
  }

  for (const doc of flatList) {
    const node = map.get(doc.id)!;
    if (doc.parentId && map.has(doc.parentId)) {
      map.get(doc.parentId)!.children.push(node);
    } else {
      roots.push(node);
    }
  }

  const sortChildren = (nodes: TreeNode[]) => {
    nodes.sort((a, b) => a.sortOrder - b.sortOrder);
    nodes.forEach((n) => sortChildren(n.children));
  };
  sortChildren(roots);

  return roots;
}

export interface FlattenedItem extends TreeNode {
  depth: number;
}

export function flattenTree(nodes: TreeNode[], expandedIds: Set<string>): FlattenedItem[] {
  const result: FlattenedItem[] = [];

  const traverse = (items: TreeNode[], depth: number) => {
    for (const item of items) {
      result.push({ ...item, depth });
      if (expandedIds.has(item.id) && item.children.length > 0) {
        traverse(item.children, depth + 1);
      }
    }
  };

  traverse(nodes, 0);
  return result;
}

export function findNodeById(nodes: TreeNode[], id: string): TreeNode | null {
  for (const node of nodes) {
    if (node.id === id) return node;
    const found = findNodeById(node.children, id);
    if (found) return found;
  }
  return null;
}

export function isAncestor(nodes: TreeNode[], ancestorId: string, descendantId: string): boolean {
  const ancestor = findNodeById(nodes, ancestorId);
  if (!ancestor) return false;
  return findNodeById(ancestor.children, descendantId) !== null;
}
