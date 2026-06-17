"""Generate FSRS-6 reference values from py-fsrs to pin the Java port's tests.
Run once, copy output into FsrsServiceTest, delete. Not part of the app."""
from datetime import datetime, timedelta, timezone

from fsrs import Card, Rating, Scheduler, State

scheduler = Scheduler(learning_steps=(), relearning_steps=(), enable_fuzzing=False)
print("params =", list(scheduler.parameters))
print("desired_retention =", scheduler.desired_retention)

t0 = datetime(2026, 1, 1, tzinfo=timezone.utc)


def first_review(rating):
    card = Card()
    card, _ = scheduler.review_card(card, rating, review_datetime=t0)
    return card


print("\n-- first review (no prior state) --")
for rating in (Rating.Hard, Rating.Good, Rating.Easy):
    c = first_review(rating)
    print(f"{rating.name}: S={c.stability:.6f} D={c.difficulty:.6f} "
          f"interval_days={(c.due - t0).days}")

print("\n-- second review after elapsed days --")
for first, second, elapsed in [
    (Rating.Good, Rating.Good, 3),
    (Rating.Good, Rating.Easy, 3),
    (Rating.Good, Rating.Hard, 3),
    (Rating.Good, Rating.Good, 10),   # late review (R lower)
    (Rating.Good, Rating.Good, 1),    # early review (R higher)
    (Rating.Easy, Rating.Good, 15),
    (Rating.Hard, Rating.Hard, 2),
]:
    c = first_review(first)
    t1 = t0 + timedelta(days=elapsed)
    c, _ = scheduler.review_card(c, second, review_datetime=t1)
    print(f"{first.name}->{second.name} elapsed={elapsed}: "
          f"S={c.stability:.6f} D={c.difficulty:.6f} interval_days={(c.due - t1).days}")

print("\n-- third review chain Good,Good,Good elapsed 3,7 --")
c = first_review(Rating.Good)
c, _ = scheduler.review_card(c, Rating.Good, review_datetime=t0 + timedelta(days=3))
c, _ = scheduler.review_card(c, Rating.Good, review_datetime=t0 + timedelta(days=10))
print(f"S={c.stability:.6f} D={c.difficulty:.6f} interval_days={(c.due - (t0 + timedelta(days=10))).days}")

print("\n-- forget / lapse path (Rating.Again on a subsequent review) --")
for first, elapsed in [
    (Rating.Good, 3),
    (Rating.Good, 10),
    (Rating.Easy, 15),
    (Rating.Hard, 2),
]:
    c = first_review(first)
    t1 = t0 + timedelta(days=elapsed)
    c, _ = scheduler.review_card(c, Rating.Again, review_datetime=t1)
    print(f"{first.name}->Again elapsed={elapsed}: "
          f"S={c.stability:.6f} D={c.difficulty:.6f} interval_days={(c.due - t1).days}")

print("\n-- lapse after a Good,Good chain (elapsed 3, then Again at +7) --")
c = first_review(Rating.Good)
c, _ = scheduler.review_card(c, Rating.Good, review_datetime=t0 + timedelta(days=3))
c, _ = scheduler.review_card(c, Rating.Again, review_datetime=t0 + timedelta(days=10))
print(f"S={c.stability:.6f} D={c.difficulty:.6f} interval_days={(c.due - (t0 + timedelta(days=10))).days}")
