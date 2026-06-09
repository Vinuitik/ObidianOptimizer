import { parseFrontmatterFields } from '../../utils/frontmatter';
import styles from './FrontmatterTable.module.css';

export default function FrontmatterTable({ frontmatter }) {
  const fields = parseFrontmatterFields(frontmatter);
  if (fields.length === 0) return null;
  return (
    <table className={styles.table}>
      <tbody>
        {fields.map(({ key, value }) => (
          <tr key={key}>
            <th scope="row">{key}</th>
            <td>{value}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
