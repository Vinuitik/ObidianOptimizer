import { useState } from 'react';
import Button from '../components/atoms/Button';
import IconButton from '../components/atoms/IconButton';
import Input from '../components/atoms/Input';
import Textarea from '../components/atoms/Textarea';
import Select from '../components/atoms/Select';
import Checkbox from '../components/atoms/Checkbox';
import Badge from '../components/atoms/Badge';
import Skeleton from '../components/atoms/Skeleton';
import Spinner from '../components/atoms/Spinner';
import Field from '../components/molecules/Field';
import Card from '../components/molecules/Card';
import Disclosure from '../components/molecules/Disclosure';
import SegmentedTabs from '../components/molecules/SegmentedTabs';
import EmptyState from '../components/molecules/EmptyState';
import ErrorState from '../components/molecules/ErrorState';
import PageHeader from '../components/molecules/PageHeader';
import ListRow from '../components/molecules/ListRow';
import useStore from '../store/useStore';
import styles from './StyleguidePage.module.css';

// Visual test bed for every primitive in every state. Route: /styleguide (not in nav).
function Section({ title, children }) {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>{title}</h2>
      <div className={styles.row}>{children}</div>
    </section>
  );
}

export default function StyleguidePage() {
  const showToast = useStore(s => s.showToast);
  const [tab, setTab] = useState('a');
  const [checked, setChecked] = useState(true);

  return (
    <div className={styles.page}>
      <PageHeader
        title="Styleguide"
        subtitle="Every primitive in every state. Obsidian Glass."
        actions={<Button variant="primary" onClick={() => showToast('Saved')}>Show toast</Button>}
      />

      <Section title="Button">
        <Button variant="primary">Primary</Button>
        <Button>Secondary</Button>
        <Button variant="ghost">Ghost</Button>
        <Button variant="danger">Danger</Button>
        <Button variant="primary" size="sm">Small</Button>
        <Button variant="primary" icon="plus">With icon</Button>
        <Button variant="primary" loading>Loading</Button>
        <Button variant="primary" disabled>Disabled</Button>
        <Button disabled>Disabled</Button>
      </Section>

      <Section title="IconButton">
        <IconButton icon="settings" aria-label="Settings" />
        <IconButton icon="plus" aria-label="Add" variant="secondary" />
        <IconButton icon="x" aria-label="Remove" variant="danger" />
        <IconButton icon="search" aria-label="Search (small)" size="sm" />
        <IconButton icon="check" aria-label="Done (disabled)" disabled />
      </Section>

      <Section title="Fields">
        <Field label="Title" hint="Shown in the sidebar">
          <Input placeholder="Untitled" />
        </Field>
        <Field label="Due date">
          <Input type="date" />
        </Field>
        <Field label="Folder" error="Pick a folder first">
          <Select options={[{ value: '', label: 'Choose…' }, { value: 'a', label: 'Inbox' }]} />
        </Field>
        <Field label="Notes">
          <Textarea placeholder="Write something" />
        </Field>
        <Field label="Disabled">
          <Input disabled defaultValue="Can't edit" />
        </Field>
        <Field label="Invalid" error="Must be a URL">
          <Input defaultValue="not a url" />
        </Field>
        <Checkbox label="Checked" checked={checked} onChange={e => setChecked(e.target.checked)} />
        <Checkbox label="Disabled" disabled />
      </Section>

      <Section title="Raw native controls (base.css only)">
        <input placeholder="raw input" />
        <input type="date" />
        <select><option>raw select</option><option>two</option></select>
        <textarea placeholder="raw textarea" />
        <button>raw button</button>
      </Section>

      <Section title="Badge">
        <Badge>Neutral</Badge>
        <Badge tone="accent">Accent</Badge>
        <Badge tone="ember">Due</Badge>
        <Badge tone="success">Synced</Badge>
        <Badge tone="danger">Failed</Badge>
      </Section>

      <Section title="Loading">
        <Spinner />
        <div className={styles.wide}><Skeleton lines={3} /></div>
        <div className={styles.wide}><Skeleton height={80} /></div>
      </Section>

      <Section title="SegmentedTabs">
        <SegmentedTabs
          tabs={[{ id: 'a', label: 'Feed', badge: 3 }, { id: 'b', label: 'Inbox' }, { id: 'c', label: 'Sources' }]}
          value={tab}
          onChange={setTab}
        />
      </Section>

      <Section title="Card / ListRow">
        <div className={styles.wide}>
          <Card>
            <Card.Header title="Card title" subtitle="Glass surface with a top sheen" actions={<Badge tone="accent">New</Badge>} />
            <ListRow title="A note title" meta="2h ago" leading={<Badge>md</Badge>} trailing={<Badge tone="ember">Due</Badge>} />
            <ListRow title="Clickable row" meta="hover me" onClick={() => {}} />
            <ListRow title="Active row" meta="selected" onClick={() => {}} active />
          </Card>
        </div>
        <div className={styles.wide}>
          <Card interactive>Interactive card</Card>
        </div>
      </Section>

      <Section title="Disclosure">
        <div className={styles.wide}>
          <Disclosure title="Advanced" storageKey="styleguide-demo">Hidden until opened. Remembers its state.</Disclosure>
        </div>
      </Section>

      <Section title="EmptyState / ErrorState">
        <div className={styles.wide}>
          <Card><EmptyState title="All caught up" description="Nothing is due. Next review tomorrow." action={{ label: 'Browse notes', onClick: () => {} }} /></Card>
        </div>
        <div className={styles.wide}>
          <Card><ErrorState message="Couldn't load failures" onRetry={() => showToast({ message: 'Retrying…', tone: 'neutral' })} /></Card>
        </div>
      </Section>

      <Section title="Toast">
        <Button onClick={() => showToast('Plain toast')}>Neutral</Button>
        <Button onClick={() => showToast({ message: 'Sent to Inbox', tone: 'success', action: { label: 'View', onClick: () => {} } })}>With action</Button>
        <Button onClick={() => showToast({ message: 'Save failed — try again', tone: 'error' })}>Error</Button>
      </Section>

      <Section title="Type">
        <div className={styles.wide}>
          <h1>Display 28 — Newsreader</h1>
          <h2>Heading 22</h2>
          <h3>Heading 18</h3>
          <p>UI text in Geist 14. <a href="#styleguide">A link</a>. <code>Geist Mono code</code></p>
        </div>
      </Section>
    </div>
  );
}
