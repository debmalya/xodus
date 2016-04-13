/**
 * Copyright 2010 - 2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.core.dataStructures.persistent;

import jetbrains.exodus.core.dataStructures.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;

public class Persistent23Tree<K extends Comparable<K>> extends AbstractPersistent23Tree<K> {

    /**
     * The root of the last version of the tree.
     */
    private volatile AbstractPersistent23Tree.RootNode<K> root;

    public Persistent23Tree() {
        this(null);
    }

    Persistent23Tree(@Nullable final AbstractPersistent23Tree.RootNode<K> root) {
        this.root = root;
    }

    public ImmutableTree<K> beginRead() {
        return new ImmutableTree<>(root);
    }

    public Persistent23Tree<K> getClone() {
        return new Persistent23Tree<>(root);
    }

    public MutableTree<K> beginWrite() {
        return new MutableTree<>(root);
    }

    public boolean endWrite(MutableTree<K> tree) {
        if (root != tree.getStartingRoot()) {
            return false;
        }
        root = tree.getRoot();
        tree.setStartingRoot(root);
        return true;
    }

    AbstractPersistent23Tree.RootNode<K> getRoot() {
        return root;
    }

    public static class ImmutableTree<K extends Comparable<K>> extends AbstractPersistent23Tree<K> {

        private final RootNode<K> root;

        ImmutableTree(@Nullable RootNode<K> root) {
            this.root = root;
        }

        @Override
        RootNode<K> getRoot() {
            return root;
        }

    }

    public static class MutableTree<K extends Comparable<K>> extends AbstractPersistent23Tree<K> {

        private RootNode<K> startingRoot;
        private RootNode<K> root;

        MutableTree(@Nullable RootNode<K> root) {
            this.startingRoot = root;
            this.root = root;
        }

        public void add(@NotNull K key) {
            if (root == null) {
                root = new RootBinaryNode<>(key, 1);
            } else {
                SplitResult<K> splitResult = root.insert(key);
                // splitResult.firstNode != null
                int size = splitResult.sizeChanged ? root.getSize() + 1 : root.getSize();
                if (splitResult.getSecondNode() == null) {
                    root = splitResult.getFirstNode().asRoot(size);
                } else {
                    root = new RootInternalBinaryNode<>(splitResult.getFirstNode(), splitResult.getKey(), splitResult.getSecondNode(), size);
                }
            }
        }

        public boolean exclude(@NotNull K key) {
            if (root == null) {
                return false;
            } else {
                Pair<Node<K>, K> removeResult = root.remove(key, true);
                if (removeResult == null) {
                    return false;
                }
                Node<K> result = removeResult.getFirst();
                if (result instanceof RemovedNode) {
                    result = result.getFirstChild();
                }
                root = result == null ? null : result.asRoot(root.getSize() - 1);
                return true;
            }
        }

        public void addAll(@NotNull final Iterable<K> keys, int size) {
            addAll(keys.iterator(), size);
        }

        public void addAll(@NotNull final Iterator<K> keys, int size) {
            if (!isEmpty()) {
                throw new UnsupportedOperationException();
            }
            root = makeRootNode(keys, size, 1);
        }

        /**
         * Create tree of next {@code size} elements from {@code iterator} of depth at least {@code depth}
         *
         * @param iterator sorted sequence of keys
         * @param size     amount of keys to take from {@code iterator}
         * @param toDepth  minimal depth of the desired tree
         * @return root of the constructed tree
         */
        @SuppressWarnings({"UnnecessaryParentheses"})
        private Node<K> makeNode(@NotNull Iterator<K> iterator, int size, int toDepth) {
            if (size <= 0) {
                return null;
            }
            int left = size;
            Node<K> node = null;
            int depth = 1;
            int minSize = 0;
            int maxSize = 0;
            while (left > 0) {
                if (depth >= toDepth) {
                    if (left <= maxSize + 1) {
                        return createNode(node, iterator.next(), makeNode(iterator, left - 1, depth - 1));
                    } else if (left <= 2 * maxSize + 2) {
                        int third = Math.max(left - 2 - maxSize, minSize);
                        return createNode(node, iterator.next(), makeNode(iterator, left - 2 - third, depth - 1), iterator.next(), makeNode(iterator, third, depth - 1));
                    }
                }
                int minUp = (1 << Math.max(toDepth, depth + 1)) - (1 << depth) - 1;
                int up = Math.max(left - 3 - 2 * maxSize, minUp);
                int third = Math.max(left - up - 3 - maxSize, minSize);
                int second = left - 3 - third - up;
                if (second >= minSize) {
                    node = createNode(node, iterator.next(), makeNode(iterator, second, depth - 1), iterator.next(), makeNode(iterator, third, depth - 1));
                    left -= second + third + 2;
                } else {
                    up = Math.max(left - 2 - maxSize, minUp);
                    second = left - 2 - up;
                    node = createNode(node, iterator.next(), makeNode(iterator, second, depth - 1));
                    left -= second + 1;
                }
                maxSize = maxSize * 3 + 2;
                minSize = minSize * 2 + 1;
                depth++;
            }
            return node;
        }

        // TODO: this copy-paste is used to prevent root nodes at non-top positions, needs polishing
        private RootNode<K> makeRootNode(@NotNull Iterator<K> iterator, int size, int toDepth) {
            if (size <= 0) {
                return null;
            }
            int left = size;
            Node<K> node = null;
            int depth = 1;
            int minSize = 0;
            int maxSize = 0;
            while (true) {
                if (depth >= toDepth) {
                    if (left <= maxSize + 1) {
                        return createRootNode(node, iterator.next(), makeNode(iterator, left - 1, depth - 1), size);
                    } else if (left <= 2 * maxSize + 2) {
                        int third = Math.max(left - 2 - maxSize, minSize);
                        return createRootNode(node, iterator.next(), makeNode(iterator, left - 2 - third, depth - 1), iterator.next(), makeNode(iterator, third, depth - 1), size);
                    }
                }
                int minUp = (1 << Math.max(toDepth, depth + 1)) - (1 << depth) - 1;
                int up = Math.max(left - 3 - 2 * maxSize, minUp);
                int third = Math.max(left - up - 3 - maxSize, minSize);
                int second = left - 3 - third - up;
                if (second >= minSize) {
                    left -= second + third + 2;
                    if (left <= 0) {
                        return createRootNode(node, iterator.next(), makeNode(iterator, second, depth - 1), iterator.next(), makeNode(iterator, third, depth - 1), size);
                    } else {
                        node = createNode(node, iterator.next(), makeNode(iterator, second, depth - 1), iterator.next(), makeNode(iterator, third, depth - 1));
                    }
                } else {
                    up = Math.max(left - 2 - maxSize, minUp);
                    second = left - 2 - up;
                    left -= second + 1;
                    if (left <= 0) {
                        return createRootNode(node, iterator.next(), makeNode(iterator, second, depth - 1), size);
                    } else {
                        node = createNode(node, iterator.next(), makeNode(iterator, second, depth - 1));
                    }
                }
                maxSize = maxSize * 3 + 2;
                minSize = minSize * 2 + 1;
                depth++;
            }
        }

        @Override
        RootNode<K> getRoot() {
            return root;
        }

        void setRoot(RootNode<K> node) {
            root = node;
        }

        RootNode<K> getStartingRoot() {
            return startingRoot;
        }

        void setStartingRoot(RootNode<K> root) {
            this.startingRoot = root;
        }

        void checkTip() {
            if (root != null) {
                checkNode(root);
            }
        }

    }
}
