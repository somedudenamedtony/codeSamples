public class CodingSample {

    // Example: Implement a function to calculate the factorial of a number using recursion.
    public static int factorial(int n) {
        if (n <= 1) {
            return 1;
        }
        return n * factorial(n - 1);
    }

    // Example: Implement a function to check if a string is a palindrome.
    public static boolean isPalindrome(String str) {
        str = str.replaceAll("[^a-zA-Z]", "").toLowerCase();
        int left = 0;
        int right = str.length() - 1;
        while (left < right) {
            if (str.charAt(left) != str.charAt(right)) {
                return false;
            }
            left++;
            right--;
        }
        return true;
    }

    // Example: Implement a class for a basic linked list node.
    public static class ListNode {
        int val;
        ListNode next;

        ListNode(int val) {
            this.val = val;
        }
    }

    // Example: Implement a function to reverse a linked list.
    public static ListNode reverseLinkedList(ListNode head) {
        ListNode prev = null;
        ListNode current = head;
        while (current != null) {
            ListNode next = current.next;
            current.next = prev;
            prev = current;
            current = next;
        }
        return prev;
    }

    public static void main(String[] args) {
        // Test the implemented functions and classes.
        System.out.println("Factorial of 5: " + factorial(5));
        System.out.println("Is 'radar' a palindrome? " + isPalindrome("radar"));

        ListNode node1 = new ListNode(1);
        ListNode node2 = new ListNode(2);
        ListNode node3 = new ListNode(3);
        node1.next = node2;
        node2.next = node3;

        System.out.println("Original Linked List:");
        ListNode current = node1;
        while (current != null) {
            System.out.print(current.val + " -> ");
            current = current.next;
        }

        ListNode reversed = reverseLinkedList(node1);
        System.out.println("\nReversed Linked List:");
        current = reversed;
        while (current != null) {
            System.out.print(current.val + " -> ");
            current = current.next;
        }
    }
}