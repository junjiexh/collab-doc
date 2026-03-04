import { useCallback, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  DndContext,
  closestCenter,
  DragEndEvent,
  DragStartEvent,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { TreeItem } from "./TreeItem";
import type { TreeNode, FlattenedItem } from "../utils/tree";
import { flattenTree, findNodeById, isAncestor } from "../utils/tree";

interface DocumentTreeProps {
  tree: TreeNode[];
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  onCreateChild: (parentId: string) => void;
  onDelete: (id: string) => void;
  onMove: (id: string, newParentId: string | null, newIndex: number) => void;
}

function SortableTreeItem({
  item,
  isActive,
  expandedIds,
  onToggle,
  onCreateChild,
  onDelete,
  onClick,
}: {
  item: FlattenedItem;
  isActive: boolean;
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  onCreateChild: (parentId: string) => void;
  onDelete: (id: string) => void;
  onClick: (id: string) => void;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: item.id });

  const style = {
    transform: CSS.Translate.toString(transform),
    transition,
  };

  return (
    <TreeItem
      ref={setNodeRef}
      id={item.id}
      title={item.title}
      depth={item.depth}
      hasChildren={item.children.length > 0}
      isExpanded={expandedIds.has(item.id)}
      isActive={isActive}
      isDragging={isDragging}
      onToggle={() => onToggle(item.id)}
      onCreateChild={() => onCreateChild(item.id)}
      onDelete={() => onDelete(item.id)}
      onClick={() => onClick(item.id)}
      handleProps={{ ...attributes, ...listeners }}
      style={style}
    />
  );
}

export default function DocumentTree({
  tree,
  expandedIds,
  onToggle,
  onCreateChild,
  onDelete,
  onMove,
}: DocumentTreeProps) {
  const navigate = useNavigate();
  const { docId } = useParams<{ docId: string }>();
  const [activeId, setActiveId] = useState<string | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  );

  const flatItems = useMemo(
    () => flattenTree(tree, expandedIds),
    [tree, expandedIds]
  );

  const handleDragStart = useCallback((event: DragStartEvent) => {
    setActiveId(String(event.active.id));
  }, []);

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      setActiveId(null);
      const { active, over, delta } = event;
      if (!over || active.id === over.id) return;

      const activeItem = flatItems.find((i) => i.id === active.id);
      const overItem = flatItems.find((i) => i.id === over.id);
      if (!activeItem || !overItem) return;

      // Prevent dropping a parent into its own descendant
      if (isAncestor(tree, String(active.id), String(over.id))) return;

      // Determine if we should nest under the target or place as sibling.
      // If dragged significantly to the right (>30px), make it a child of the over item.
      const INDENT_THRESHOLD = 30;
      const shouldNest =
        delta.x > INDENT_THRESHOLD &&
        overItem.id !== activeItem.parentId; // don't re-nest under current parent

      let newParentId: string | null;
      let newIndex: number;

      if (shouldNest) {
        // Make it the last child of the over item
        newParentId = overItem.id;
        const overNode = findNodeById(tree, overItem.id);
        newIndex = overNode
          ? overNode.children.filter((c) => c.id !== activeItem.id).length
          : 0;
      } else {
        // Place as sibling of the over item
        newParentId = overItem.parentId;
        // Find siblings in the tree (not flatItems, which is filtered by expanded state)
        let siblingList: TreeNode[];
        if (newParentId) {
          const parentNode = findNodeById(tree, newParentId);
          siblingList = parentNode ? parentNode.children : [];
        } else {
          siblingList = tree;
        }
        const filtered = siblingList.filter((s) => s.id !== activeItem.id);
        const overIdx = filtered.findIndex((s) => s.id === overItem.id);
        // Place after or before based on vertical direction
        const activeIdx = flatItems.findIndex((i) => i.id === active.id);
        const overFlatIdx = flatItems.findIndex((i) => i.id === over.id);
        const movingDown = activeIdx < overFlatIdx;
        newIndex = overIdx === -1 ? 0 : movingDown ? overIdx + 1 : overIdx;
      }

      onMove(String(active.id), newParentId, newIndex);
    },
    [flatItems, tree, onMove]
  );

  const activeItem = activeId
    ? flatItems.find((i) => i.id === activeId)
    : null;

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
    >
      <SortableContext
        items={flatItems.map((i) => i.id)}
        strategy={verticalListSortingStrategy}
      >
        {flatItems.map((item) => (
          <SortableTreeItem
            key={item.id}
            item={item}
            isActive={docId === item.id}
            expandedIds={expandedIds}
            onToggle={onToggle}
            onCreateChild={onCreateChild}
            onDelete={onDelete}
            onClick={(id) => navigate(`/doc/${id}`)}
          />
        ))}
      </SortableContext>

      <DragOverlay>
        {activeItem ? (
          <TreeItem
            id={activeItem.id}
            title={activeItem.title}
            depth={0}
            hasChildren={activeItem.children.length > 0}
            isExpanded={false}
            isActive={false}
            isDragging
            onToggle={() => {}}
            onCreateChild={() => {}}
            onDelete={() => {}}
          />
        ) : null}
      </DragOverlay>
    </DndContext>
  );
}
