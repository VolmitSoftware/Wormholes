package art.arcane.wormholes.menu;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public sealed interface MenuResult permits MenuResult.Stay, MenuResult.Navigate, MenuResult.Back, MenuResult.Close,
    MenuResult.Confirm, MenuResult.Pending, MenuResult.Deferred, MenuResult.Page, MenuResult.Suspend {
    static MenuResult stay() {
        return new Stay(null);
    }

    static MenuResult stay(MenuNotice notice) {
        return new Stay(notice);
    }

    static MenuResult push(MenuRoute route) {
        return new Navigate(route, NavigateMode.PUSH, null, null);
    }

    static MenuResult navigate(MenuRoute route, NavigateMode mode, MenuNotice notice) {
        return new Navigate(route, mode, notice, null);
    }

    static MenuResult navigate(MenuRoute route, NavigateMode mode, int page, MenuNotice notice) {
        return new Navigate(route, mode, notice, page);
    }

    static MenuResult back() {
        return new Back(null);
    }

    static MenuResult back(MenuNotice notice) {
        return new Back(notice);
    }

    static MenuResult close() {
        return new Close(null, null);
    }

    static MenuResult close(MenuNotice notice) {
        return new Close(notice, null);
    }

    static MenuResult closeThen(Supplier<MenuNotice> afterClose) {
        return new Close(null, Objects.requireNonNull(afterClose, "afterClose"));
    }

    static MenuResult confirm(MenuConfirmRequest request) {
        return new Confirm(request);
    }

    static MenuResult pending(CompletableFuture<MenuResult> future) {
        return new Pending(future, null);
    }

    static MenuResult pending(CompletableFuture<MenuResult> future, MenuNotice notice) {
        return new Pending(future, notice);
    }

    static MenuResult deferred(Supplier<MenuResult> result) {
        return new Deferred(result);
    }

    static MenuResult page(int page) {
        return new Page(page);
    }

    static MenuResult suspend() {
        return new Suspend();
    }

    record Stay(MenuNotice notice) implements MenuResult {
    }

    record Navigate(MenuRoute route, NavigateMode mode, MenuNotice notice, Integer page) implements MenuResult {
        public Navigate {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(mode, "mode");
            if (page != null && page < 0) {
                throw new IllegalArgumentException("page " + page + " must not be negative");
            }
        }
    }

    record Back(MenuNotice notice) implements MenuResult {
    }

    record Close(MenuNotice notice, Supplier<MenuNotice> afterClose) implements MenuResult {
    }

    record Confirm(MenuConfirmRequest request) implements MenuResult {
        public Confirm {
            Objects.requireNonNull(request, "request");
        }
    }

    record Pending(CompletableFuture<MenuResult> future, MenuNotice notice) implements MenuResult {
        public Pending {
            Objects.requireNonNull(future, "future");
        }
    }

    record Deferred(Supplier<MenuResult> result) implements MenuResult {
        public Deferred {
            Objects.requireNonNull(result, "result");
        }
    }

    record Page(int page) implements MenuResult {
        public Page {
            if (page < 0) {
                throw new IllegalArgumentException("page " + page + " must not be negative");
            }
        }
    }

    record Suspend() implements MenuResult {
    }
}
