package com.github.mproberts.rxtools.list;

import rx.Emitter;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.internal.operators.OnSubscribeCreate;
import rx.subscriptions.CompositeSubscription;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class ConcatObservableList extends BaseObservableList
{
    private final List<ListSubscription> _subscriptions = new ArrayList<>();

    private final ObservableList<ObservableList<?>> _lists;
    private final CompositeSubscription _subscription = new CompositeSubscription();
    private Observable<Update> _updateObservable;

    private class ListSubscription implements Action1<Update>
    {
        private final ObservableList<?> _observableList;
        private final List<Change> _initialChanges;
        private Subscription _subscription;
        private AtomicBoolean _alreadyRunning = new AtomicBoolean(true);
        private List<?> _latest;
        private int _index;

        public ListSubscription(int index, List<Change> initialChanges, ObservableList<?> observableList)
        {
            _initialChanges = initialChanges;
            _observableList = observableList;
            _subscription = _observableList
                    .updates()
                    .subscribe(this);

            _index = index;
            _alreadyRunning.set(false);
        }

        public int size()
        {
            return _latest == null ? 0 : _latest.size();
        }

        public void setIndex(int index)
        {
            _index = index;
        }

        public void unsubscribe()
        {
            _subscription.unsubscribe();
        }

        @Override
        public void call(final Update update)
        {
            _latest = update.list;

            if (_alreadyRunning.get()) {
                // enqueue changes immediately
                _initialChanges.addAll(update.changes);
            }
            else {
                applyUpdate(new Func1<List, Update>() {
                    @Override
                    public Update call(List list)
                    {
                        int offset = 0;

                        for (int i = 0; i < _index; ++i) {
                            offset += _subscriptions.get(i).size();
                        }

                        _latest = update.list;

                        List<Change> changes = adjustChanges(offset, offset, update.changes);

                        return new Update(getCurrentList(), changes);
                    }
                });
            }
        }

        public List<?> list()
        {
            return _latest;
        }
    }
    class ConcatUpdateSubscription implements Action1<Update<ObservableList<?>>>, Subscription
    {
        final AtomicBoolean _isFirst = new AtomicBoolean(true);
        private Emitter<Update> _firstEmitter;
        private AtomicInteger _refCount = new AtomicInteger(0);
        private Subscription _subscription;

        ConcatUpdateSubscription(Emitter<Update> firstEmitter)
        {
            _firstEmitter = firstEmitter;
        }

        @Override
        public void call(final Update<ObservableList<?>> listsUpdate)
        {
            boolean isFirstEmission = _isFirst.getAndSet(false);

            if (isFirstEmission) {
                int i = 0;
                for (ObservableList<?> observableList : listsUpdate.list) {
                    addSubscription(i++, listsUpdate.list);
                }

                ConcatList currentList = getCurrentList();

                List oldPreviousList = setPreviousList(currentList);

                // if this is the very first emission, we are responsible for forcing
                // the emission of the reload, otherwise, the base observable
                // will step in and emit on subscribe
                if (oldPreviousList == null) {
                    _firstEmitter.onNext(new Update(currentList, Change.reloaded()));
                }
            }
            else {
                List<Change> changes = new ArrayList<>();

                for (Change change : listsUpdate.changes) {
                    int fromOffset = 0;
                    int toOffset = 0;

                    for (int i = 0; i < change.from; ++i) {
                        fromOffset += _subscriptions.get(i).size();
                    }

                    for (int i = 0; i < change.to; ++i) {
                        toOffset += _subscriptions.get(i).size();
                    }

                    switch (change.type) {
                        case Inserted: {
                            addSubscription(change.to, listsUpdate.list);

                            for (int i = change.to + 1; i < _subscriptions.size(); ++i) {
                                ListSubscription subscription = _subscriptions.get(i);

                                subscription.setIndex(i + 1);
                            }

                            ListSubscription subscription = _subscriptions.get(change.to);

                            for (int i = 0; i < subscription.list().size(); ++i) {
                                changes.add(Change.inserted(i + toOffset));
                            }
                            break;
                        }
                        case Moved: {

                            ListSubscription subscription = _subscriptions.remove(change.from);

                            _subscriptions.add(change.to, subscription);
                            subscription.setIndex(change.to);

                            if (change.from < change.to) {
                                for (int i = change.from; i < change.to; ++i) {
                                    ListSubscription movedSubscription = _subscriptions.get(i);

                                    movedSubscription.setIndex(i);
                                }
                            }
                            else {
                                for (int i = change.to + 1; i <= change.from; ++i) {
                                    ListSubscription movedSubscription = _subscriptions.get(i);

                                    movedSubscription.setIndex(i);
                                }
                            }

                            toOffset = 0;

                            for (int i = 0; i < change.to; ++i) {
                                toOffset += _subscriptions.get(i).size();
                            }

                            for (int i = 0; i < subscription.list().size(); ++i) {
                                changes.add(Change.moved(i + fromOffset, i + toOffset));
                            }
                            break;
                        }
                        case Removed: {
                            ListSubscription subscription = _subscriptions.remove(change.from);
                            subscription.unsubscribe();

                            for (int i = 0; i < subscription.list().size(); ++i) {
                                changes.add(Change.removed(i + fromOffset));
                            }
                            break;
                        }
                        case Reloaded: {
                            changes.add(Change.reloaded());

                            for (ListSubscription subscription : _subscriptions) {
                                subscription.unsubscribe();
                            }

                            _subscriptions.clear();

                            for (int i = 0; i < listsUpdate.list.size(); ++i) {
                                addSubscription(i++, listsUpdate.list);
                            }
                            break;
                        }
                    }
                }

                ConcatList currentList = getCurrentList();

                setPreviousList(currentList);

                _firstEmitter.onNext(new Update(currentList, changes));
            }
        }

        @Override
        public void unsubscribe()
        {
            int subscriptions = _refCount.decrementAndGet();

            if (subscriptions == 0) {
                for (ListSubscription subscription : _subscriptions) {
                    subscription.unsubscribe();
                }

                _subscriptions.clear();

                _updateObservable = null;
                _subscription.unsubscribe();
            }
        }

        @Override
        public boolean isUnsubscribed()
        {
            return _refCount.get() == 0;
        }

        public void setSubscription(Subscription subscription)
        {
            _subscription = subscription;
        }

        public void subscribe()
        {
            _refCount.incrementAndGet();
        }
    }

    class ConcatOnSubscribeAction implements Action1<Emitter<Update>>
    {
        ConcatUpdateSubscription _subscriber;

        @Override
        public void call(final Emitter<Update> updateEmitter)
        {
            if (_subscriber == null) {
                _subscriber = new ConcatUpdateSubscription(updateEmitter);

                final Subscription subscribe = _lists.updates()
                        .subscribe(_subscriber);

                _subscriber.setSubscription(subscribe);
            }

            _subscriber.subscribe();

            final AtomicBoolean isUnsubscribed = new AtomicBoolean();

            updateEmitter.setSubscription(new Subscription() {
                @Override
                public void unsubscribe()
                {
                    _subscriber.unsubscribe();

                    isUnsubscribed.set(true);
                }

                @Override
                public boolean isUnsubscribed()
                {
                    return isUnsubscribed.get();
                }
            });
        }
    }

    public Observable<Update> createUpdater()
    {
        if (_updateObservable != null) {
            return _updateObservable;
        }

        _updateObservable = Observable.unsafeCreate(new OnSubscribeCreate<>(
                        new ConcatOnSubscribeAction(),
                        Emitter.BackpressureMode.LATEST));

        return _updateObservable;
    }

    ConcatObservableList(ObservableList<ObservableList<?>> lists)
    {
        _lists = lists;
    }

    private ConcatList getCurrentList()
    {
        List[] lists = new List[_subscriptions.size()];
        int i = 0;

        for (ListSubscription subscription : _subscriptions) {
            lists[i++] = subscription.list();
        }

        return new ConcatList(lists);
    }

    private List<Change> adjustChanges(int fromOffset, int toOffset, List<Change> changes)
    {
        List<Change> updatedChanges = new ArrayList<>(changes.size());

        for (Change change : changes) {
            switch (change.type) {
                case Inserted:
                    updatedChanges.add(Change.inserted(change.to + toOffset));
                    break;
                case Removed:
                    updatedChanges.add(Change.removed(change.from + fromOffset));
                    break;
                case Moved:
                    updatedChanges.add(Change.moved(
                            change.from + fromOffset,
                            change.to + toOffset));
                    break;
                case Reloaded:
                    updatedChanges.add(Change.reloaded());
                    break;
            }
        }

        return updatedChanges;
    }

    private List<Change> addSubscription(int position, List<ObservableList<?>> lists)
    {
        int offset = 0;

        for (int i = 0; i < position; ++i) {
            offset += _subscriptions.get(i).size();
        }

        ObservableList<?> observableList = lists.get(position);
        List<Change> changes = new ArrayList<>();

        _subscriptions.add(position, new ListSubscription(position, changes, observableList));

        // populate initial adds if there is an emission from
        // the list already
        changes = adjustChanges(offset, offset, changes);

        return changes;
    }

    @Override
    public Observable updates()
    {
        return super.updates().mergeWith(createUpdater());
    }
}
