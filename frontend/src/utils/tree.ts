import { arrayMove } from "@dnd-kit/sortable";
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

export function flattenTreeFull(nodes: TreeNode[]): FlattenedItem[] {
  const result: FlattenedItem[] = [];
  const traverse = (items: TreeNode[], depth: number) => {
    for (const item of items) {
      result.push({ ...item, depth });
      if (item.children.length > 0) {
        traverse(item.children, depth + 1);
      }
    }
  };
  traverse(nodes, 0);
  return result;
}

function getDragDepth(offset: number, indentationWidth: number) {
  return Math.round(offset / indentationWidth);
}

function getMaxDepth(previousItem: FlattenedItem | undefined) {
  return previousItem ? previousItem.depth + 1 : 0;
}

function getMinDepth(nextItem: FlattenedItem | undefined) {
  return nextItem ? nextItem.depth : 0;
}

export function getProjection(
  items: FlattenedItem[],
  activeId: string,
  overId: string,
  dragOffset: number,
  indentationWidth: number
) {
  const overItemIndex = items.findIndex(({ id }) => id === overId);
  const activeItemIndex = items.findIndex(({ id }) => id === activeId);
  const activeItem = items[activeItemIndex];
  const newItems = arrayMove(items, activeItemIndex, overItemIndex);
  const previousItem = newItems[overItemIndex - 1];
  const nextItem = newItems[overItemIndex + 1];
  const dragDepth = getDragDepth(dragOffset, indentationWidth);
  const projectedDepth = activeItem.depth + dragDepth;
  const maxDepth = getMaxDepth(previousItem);
  const minDepth = getMinDepth(nextItem);
  let depth = projectedDepth;

  if (projectedDepth >= maxDepth) depth = maxDepth;
  else if (projectedDepth < minDepth) depth = minDepth;

  function getParentId(): string | null {
    if (depth === 0 || !previousItem) return null;
    if (depth === previousItem.depth) return previousItem.parentId;
    if (depth > previousItem.depth) return previousItem.id;
    const newParent = newItems
      .slice(0, overItemIndex)
      .reverse()
      .find((item) => item.depth === depth)?.parentId;
    return newParent ?? null;
  }

  return { depth, maxDepth, minDepth, parentId: getParentId() };
}

export function removeChildrenOf(
  items: FlattenedItem[],
  ids: string[]
): FlattenedItem[] {
  const excludeParentIds = [...ids];
  return items.filter((item) => {
    if (item.parentId && excludeParentIds.includes(item.parentId)) {
      if (item.children.length) excludeParentIds.push(item.id);
      return false;
    }
    return true;
  });
}
