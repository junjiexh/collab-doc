import { useCallback, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  DndContext,
  closestCenter,
  DragEndEvent,
  DragStartEvent,
  DragMoveEvent,
  DragOverEvent,
  DragCancelEvent,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  MeasuringStrategy,
} from "@dnd-kit/core";
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
  AnimateLayoutChanges,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { TreeItem } from "./TreeItem";
import type { TreeNode, FlattenedItem } from "../utils/tree";
import {
  flattenTree,
  flattenTreeFull,
  findNodeById,
  isAncestor,
  getProjection,
  removeChildrenOf,
} from "../utils/tree";

const INDENTATION_WIDTH = 20;

const measuring = {
  droppable: {
    strategy: MeasuringStrategy.Always,
  },
};

const animateLayoutChanges: AnimateLayoutChanges = ({
  isSorting,
  wasDragging,
}) => (isSorting || wasDragging ? false : true);

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
  depth,
  isActive,
  expandedIds,
  onToggle,
  onCreateChild,
  onDelete,
  onClick,
}: {
  item: FlattenedItem;
  depth: number;
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
  } = useSortable({ id: item.id, animateLayoutChanges });

  const style = {
    transform: CSS.Translate.toString(transform),
    transition,
  };

  return (
    <TreeItem
      ref={setNodeRef}
      id={item.id}
      title={item.title}
      depth={depth}
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
  const [overId, setOverId] = useState<string | null>(null);
  const [offsetLeft, setOffsetLeft] = useState(0);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  );

  // Fully flattened tree (all nodes expanded) for projection calculations
  const flatItemsFull = useMemo(() => flattenTreeFull(tree), [tree]);

  // During drag, remove children of active item so they collapse visually
  const sortedIds = useMemo(() => {
    const items = activeId
      ? removeChildrenOf(flatItemsFull, [activeId])
      : flatItemsFull;
    return items.map((i) => i.id);
  }, [flatItemsFull, activeId]);

  // Items for rendering: use expandedIds normally, but during drag use the
  // full flatten with active's children removed
  const flatItems = useMemo(() => {
    if (activeId) {
      return removeChildrenOf(flatItemsFull, [activeId]);
    }
    return flattenTree(tree, expandedIds);
  }, [tree, expandedIds, flatItemsFull, activeId]);

  const projected =
    activeId && overId
      ? getProjection(
          flatItems,
          activeId,
          overId,
          offsetLeft,
          INDENTATION_WIDTH
        )
      : null;

  const handleDragStart = useCallback((event: DragStartEvent) => {
    setActiveId(String(event.active.id));
    setOverId(String(event.active.id));
    setOffsetLeft(0);
  }, []);

  const handleDragMove = useCallback((event: DragMoveEvent) => {
    setOffsetLeft(event.delta.x);
  }, []);

  const handleDragOver = useCallback((event: DragOverEvent) => {
    if (event.over) {
      setOverId(String(event.over.id));
    }
  }, []);

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      resetState();

      if (!over || !projected) return;

      const activeItemData = flatItems.find((i) => i.id === active.id);
      if (!activeItemData) return;

      const { parentId: newParentId } = projected;

      // If nothing changed (same position & same parent), skip
      if (active.id === over.id && activeItemData.parentId === newParentId)
        return;

      // Prevent dropping a parent into its own descendant
      if (newParentId && isAncestor(tree, String(active.id), newParentId))
        return;

      // Calculate newIndex among siblings
      let siblingList: TreeNode[];
      if (newParentId) {
        const parentNode = findNodeById(tree, newParentId);
        siblingList = parentNode ? parentNode.children : [];
      } else {
        siblingList = tree;
      }

      const filteredSiblings = siblingList.filter(
        (s) => s.id !== String(active.id)
      );

      let newIndex: number;

      if (active.id === over.id) {
        // Only depth changed (horizontal drag) — place at end of new parent
        newIndex = filteredSiblings.length;
      } else {
        const overItem = flatItems.find((i) => i.id === over.id);
        if (!overItem) return;

        const overSiblingIdx = filteredSiblings.findIndex(
          (s) => s.id === overItem.id
        );

        if (overSiblingIdx !== -1) {
          const activeIndex = flatItems.findIndex((i) => i.id === active.id);
          const overIndex = flatItems.findIndex((i) => i.id === over.id);
          const movingDown = activeIndex < overIndex;
          newIndex = movingDown ? overSiblingIdx + 1 : overSiblingIdx;
        } else {
          newIndex = filteredSiblings.length;
        }
      }

      onMove(String(active.id), newParentId, newIndex);
    },
    [flatItems, tree, onMove, projected]
  );

  const handleDragCancel = useCallback((_event: DragCancelEvent) => {
    resetState();
  }, []);

  function resetState() {
    setActiveId(null);
    setOverId(null);
    setOffsetLeft(0);
  }

  const activeItem = activeId
    ? flatItems.find((i) => i.id === activeId)
    : null;

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      measuring={measuring}
      onDragStart={handleDragStart}
      onDragMove={handleDragMove}
      onDragOver={handleDragOver}
      onDragEnd={handleDragEnd}
      onDragCancel={handleDragCancel}
    >
      <SortableContext
        items={sortedIds}
        strategy={verticalListSortingStrategy}
      >
        {flatItems.map((item) => (
          <SortableTreeItem
            key={item.id}
            item={item}
            depth={
              item.id === activeId && projected
                ? projected.depth
                : item.depth
            }
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
            depth={projected ? projected.depth : 0}
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
